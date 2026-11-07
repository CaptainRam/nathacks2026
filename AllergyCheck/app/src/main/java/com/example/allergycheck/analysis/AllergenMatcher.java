package com.example.allergycheck.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds evidence for one {@link Allergen} in a {@link ParsedLabel}.
 *
 * <p>Matching works on word tokens, never substrings, so "buckwheat" is not "wheat" and
 * "eggplant" is not "egg". Three outcomes per piece of text, strongest first:
 * <ol>
 *   <li><b>exact term</b> — a firm finding (CONTAINS or MAY_CONTAIN depending on where it sits);</li>
 *   <li><b>ambiguous term</b> (ingredients only) — POSSIBLE;</li>
 *   <li><b>near miss</b> (one typo away from a term) — POSSIBLE, to absorb OCR misreads.</li>
 * </ol>
 * At most one finding is produced per ingredient/statement per allergen.
 */
final class AllergenMatcher {

    /** Fuzzy matching only for terms this long; shorter words collide with ordinary vocabulary. */
    private static final int FUZZY_MIN_LENGTH = 6;

    /** Everyday words one typo away from a term (batter/butter) that must not trigger fuzzy hits. */
    private static final Set<String> FUZZY_STOPLIST = new HashSet<>(Arrays.asList(
            "better", "batter", "bitter", "butler", "cheers", "lactase", "creams", "sodium"));

    private AllergenMatcher() {
    }

    static List<Finding> match(ParsedLabel label, Allergen allergen) {
        List<Finding> findings = new ArrayList<>();

        for (IngredientItem item : label.ingredients()) {
            Finding finding = matchText(allergen, item.matchText(), item.original(), Finding.Source.INGREDIENT);
            if (finding != null) {
                findings.add(finding);
            }
        }
        for (ParsedLabel.Statement statement : label.statements()) {
            Finding.Source source = statement.kind() == ParsedLabel.Kind.CONTAINS
                    ? Finding.Source.CONTAINS_STATEMENT
                    : Finding.Source.MAY_CONTAIN_STATEMENT;
            Finding finding = matchText(allergen, statement.text(), statement.text(), source);
            if (finding != null) {
                findings.add(finding);
            }
        }
        return findings;
    }

    private static Finding matchText(Allergen allergen, String text, String evidence, Finding.Source source) {
        List<String> tokens = TextUtil.tokens(text);
        if (tokens.isEmpty()) {
            return null;
        }
        boolean[] excluded = new boolean[tokens.size()];
        for (String[] phrase : allergen.exclusions()) {
            markPhrase(tokens, phrase, excluded);
        }

        // 1. Exact terms.
        for (String[] term : allergen.terms()) {
            int at = indexOfPhrase(tokens, term, excluded);
            if (at >= 0) {
                Finding.Level level = source == Finding.Source.MAY_CONTAIN_STATEMENT
                        ? Finding.Level.MAY_CONTAIN : Finding.Level.CONTAINS;
                return new Finding(allergen, level, source, Finding.Reason.NONE, join(term), evidence);
            }
        }

        // 2. Ambiguous terms — meaningful only in an ingredient list, where they are an ingredient.
        if (source == Finding.Source.INGREDIENT) {
            for (String[] term : allergen.ambiguousTerms()) {
                int at = indexOfPhrase(tokens, term, excluded);
                if (at >= 0) {
                    return new Finding(allergen, Finding.Level.POSSIBLE, source,
                            Finding.Reason.AMBIGUOUS_INGREDIENT, join(term), evidence);
                }
            }
        }

        // 3. Near misses, to survive OCR typos ("peanu1", "pean ut").
        String near = nearMiss(allergen, tokens, excluded);
        if (near != null) {
            return new Finding(allergen, Finding.Level.POSSIBLE, source, Finding.Reason.UNCERTAIN_READ, near, evidence);
        }
        return null;
    }

    private static String nearMiss(Allergen allergen, List<String> tokens, boolean[] excluded) {
        for (String[] term : allergen.terms()) {
            if (term.length != 1 || term[0].length() < FUZZY_MIN_LENGTH) {
                continue;
            }
            String word = term[0];
            for (int i = 0; i < tokens.size(); i++) {
                if (excluded[i]) {
                    continue;
                }
                if (isNear(tokens.get(i), word)) {
                    return word;
                }
                // A word split by OCR: "pean" + "ut".
                if (i + 1 < tokens.size() && !excluded[i + 1] && isNear(tokens.get(i) + tokens.get(i + 1), word)) {
                    return word;
                }
            }
        }
        return null;
    }

    private static boolean isNear(String token, String term) {
        if (token.length() < FUZZY_MIN_LENGTH - 1 || FUZZY_STOPLIST.contains(token)) {
            return false;
        }
        return TextUtil.levenshtein(token, term, 1) <= 1 || TextUtil.levenshtein(token, term + "s", 1) <= 1;
    }

    /** Sets {@code excluded[i]} for every token covered by an occurrence of {@code phrase}. */
    private static void markPhrase(List<String> tokens, String[] phrase, boolean[] excluded) {
        int from = 0;
        while (true) {
            int at = indexOfPhrase(tokens, phrase, null, from);
            if (at < 0) {
                return;
            }
            for (int i = at; i < at + phrase.length; i++) {
                excluded[i] = true;
            }
            from = at + 1;
        }
    }

    private static int indexOfPhrase(List<String> tokens, String[] phrase, boolean[] excluded) {
        return indexOfPhrase(tokens, phrase, excluded, 0);
    }

    /**
     * Index of the first occurrence of {@code phrase} in {@code tokens} (plural-tolerant, and
     * skipping occurrences that start on an excluded token or are followed by "free"), or -1.
     */
    private static int indexOfPhrase(List<String> tokens, String[] phrase, boolean[] excluded, int from) {
        for (int start = from; start + phrase.length <= tokens.size(); start++) {
            boolean ok = true;
            for (int k = 0; k < phrase.length && ok; k++) {
                if (excluded != null && excluded[start + k]) {
                    ok = false;
                } else {
                    ok = sameWord(tokens.get(start + k), phrase[k]);
                }
            }
            if (!ok) {
                continue;
            }
            int next = start + phrase.length;
            // "peanut free", "gluten free": the label is saying the opposite.
            if (excluded != null && next < tokens.size() && tokens.get(next).equals("free")) {
                continue;
            }
            return start;
        }
        return -1;
    }

    private static boolean sameWord(String token, String term) {
        if (token.equals(term) || token.equals(term + "s") || token.equals(term + "es")) {
            return true;
        }
        return term.endsWith("y") && token.equals(term.substring(0, term.length() - 1) + "ies");
    }

    private static String join(String[] tokens) {
        return String.join(" ", tokens);
    }
}
