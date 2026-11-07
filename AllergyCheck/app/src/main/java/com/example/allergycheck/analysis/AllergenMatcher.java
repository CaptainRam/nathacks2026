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

    /** Digits OCR commonly produces for letters, and the letters each can stand for (same index). */
    private static final String DIGIT_LOOKALIKES = "01345678";
    private static final String[] LOOKALIKE_LETTERS = {"o", "lit", "e", "a", "s", "g", "t", "b"};

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

        // 2. Near misses, to survive OCR typos ("peanu1", "mi1k", "pean ut"). Checked before the
        //    ambiguous terms so the more specific explanation ("looks like a misread of …") wins.
        String near = nearMiss(allergen, tokens, excluded);
        if (near != null) {
            return new Finding(allergen, Finding.Level.POSSIBLE, source, Finding.Reason.UNCERTAIN_READ, near, evidence);
        }

        // 3. Ambiguous terms — meaningful only in an ingredient list, where they are an ingredient.
        if (source == Finding.Source.INGREDIENT) {
            for (String[] term : allergen.ambiguousTerms()) {
                int at = indexOfPhrase(tokens, term, excluded);
                if (at >= 0) {
                    return new Finding(allergen, Finding.Level.POSSIBLE, source,
                            Finding.Reason.AMBIGUOUS_INGREDIENT, join(term), evidence);
                }
            }
        }
        return null;
    }

    /**
     * A single-word term that the text almost spells. Exact spellings never count here: if one was
     * rejected in step 1 (an exclusion, or "… free"), it was read correctly and means the opposite.
     */
    private static String nearMiss(Allergen allergen, List<String> tokens, boolean[] excluded) {
        for (String[] term : allergen.terms()) {
            if (term.length != 1) {
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
                // A word split by OCR: "pean" + "ut". Here an exact join is the point.
                if (word.length() >= FUZZY_MIN_LENGTH && i + 1 < tokens.size() && !excluded[i + 1]) {
                    String joined = tokens.get(i) + tokens.get(i + 1);
                    if (joined.equals(word) || isNear(joined, word)) {
                        return word;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isNear(String token, String term) {
        if (FUZZY_STOPLIST.contains(token)) {
            return false;
        }
        if (digitConfusable(token, term)) {
            return true;
        }
        if (term.length() < FUZZY_MIN_LENGTH || token.length() < FUZZY_MIN_LENGTH - 1) {
            return false;
        }
        int plain = TextUtil.levenshtein(token, term, 1);
        int plural = TextUtil.levenshtein(token, term + "s", 1);
        return (plain == 1 || plural == 1) && plain != 0 && plural != 0;
    }

    /**
     * OCR often reads a letter as a look-alike digit. For short words, where general typo tolerance
     * would collide with ordinary vocabulary, accept only that specific confusion
     * ({@code mi1k}, {@code whea1}, {@code s0y}): same length, every difference a digit standing in
     * for the letter it resembles.
     */
    private static boolean digitConfusable(String token, String term) {
        if (term.length() < 3 || token.length() != term.length()) {
            return false;
        }
        boolean sawDigit = false;
        for (int i = 0; i < token.length(); i++) {
            char t = token.charAt(i);
            char w = term.charAt(i);
            if (t == w) {
                continue;
            }
            if (Character.isDigit(t) && DIGIT_LOOKALIKES.indexOf(t) >= 0
                    && LOOKALIKE_LETTERS[DIGIT_LOOKALIKES.indexOf(t)].indexOf(w) >= 0) {
                sawDigit = true;
            } else {
                return false;
            }
        }
        return sawDigit;
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
