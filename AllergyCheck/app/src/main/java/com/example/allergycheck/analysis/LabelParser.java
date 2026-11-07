package com.example.allergycheck.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the raw text of a food label into a {@link ParsedLabel}: a clean ingredient list plus
 * separate "contains" and "may contain" statements.
 *
 * <p>Pure and deterministic — no I/O, no Android — so every rule can be unit-tested. Design bias:
 * when unsure, <em>keep</em> text. Dropping a line could hide an allergen; keeping noise only
 * costs a harmless extra list entry.
 */
public final class LabelParser {

    private static final char BREAK = '\u0001';

    /** "Ingredients:", "Ingrédients", "Ingredient list" … */
    private static final Pattern HEADING = Pattern.compile(
            "(?i)\\b(?:ingr[eé]dients?|ingredientes)\\b(?:\\s+list)?\\s*[:\\-–]?");

    /** Where a statement begins mid-text, so it can be split off from the ingredients before it. */
    private static final Pattern STATEMENT_START = Pattern.compile(
            "(?i)\\b(?:may contain|might contain|peut contenir|contains?\\s*:|contient\\s*:"
                    + "|(?:produced|made|manufactured|processed|packaged|packed)\\s+(?:in|on)\\s+(?:a|an|the)?\\s*"
                    + "(?:facility|plant|factory|equipment|line)|traces? (?:of|de))");

    private static final Pattern MAY_CONTAIN = Pattern.compile(
            "^(?:may contain|might contain|could contain|peut contenir|traces? (?:of|de)"
                    + "|(?:produced|made|manufactured|processed|packaged|packed) (?:in|on) (?:a |an |the )?"
                    + "(?:facility|plant|factory|equipment|line)).*");

    /** "Contains:" / "Contient" — but not "Contains 2% or less of:", which is an ingredient prefix. */
    private static final Pattern CONTAINS = Pattern.compile("^(?:contains?|contient)(?:\\s+(?!\\d).*)?$");

    /** Everything from here on belongs to a different panel of the package. */
    private static final Pattern TERMINATOR = Pattern.compile(
            "(?i)\\b(?:nutrition(?:al)? (?:facts|information)|valeur nutritive|distributed by|distribu[eé] par"
                    + "|manufactured (?:by|for)|best before|meilleur avant|net w(?:eigh)?t|keep refrigerated"
                    + "|store in)\\b");

    private static final Pattern ALLERGEN_PREFIX = Pattern.compile(
            "(?i)^\\s*(?:allerg(?:en|y|ens|ies)\\s*(?:information|advice|warning|statement|info)?\\s*[:\\-–]?)\\s*");

    private LabelParser() {
    }

    public static ParsedLabel parse(String rawText) {
        String raw = rawText == null ? "" : rawText;
        String text = prepare(raw);

        // Heading: keep what follows the first one; later ones (a second language) become breaks.
        Matcher heading = HEADING.matcher(text);
        boolean headingFound = heading.find();
        int ingredientsStart = headingFound ? heading.end() : 0;
        String beforeHeading = headingFound ? text.substring(0, heading.start()) : "";
        String afterHeading = HEADING.matcher(text.substring(ingredientsStart)).replaceAll(String.valueOf(BREAK) + ". ");

        List<ParsedLabel.Statement> statements = new ArrayList<>();
        List<IngredientItem> ingredients = new ArrayList<>();

        // Text before the heading can only contribute statements (e.g. "CONTAINS: MILK" on the front).
        for (String segment : segments(beforeHeading)) {
            ParsedLabel.Statement statement = asStatement(segment);
            if (statement != null) {
                statements.add(statement);
            }
        }

        boolean ingredientsEnded = false;
        for (String segment : segments(afterHeading)) {
            ParsedLabel.Statement statement = asStatement(segment);
            if (statement != null) {
                statements.add(statement);
                continue;
            }
            if (ingredientsEnded) {
                continue;
            }
            Matcher terminator = TERMINATOR.matcher(segment);
            if (terminator.find()) {
                segment = segment.substring(0, terminator.start());
                ingredientsEnded = true;
            }
            addIngredients(segment, 0, ingredients);
        }
        return new ParsedLabel(raw, headingFound, ingredients, statements);
    }

    /**
     * Builds a label from an ingredient list that some other reader already pulled out of
     * {@code rawText} — e.g. {@code ingredients.IngredientExtractor} — while still taking the
     * "contains" / "may contain" statements from the full text, since those are printed outside
     * the ingredient list.
     *
     * <p>Each supplied item is cleaned and expanded exactly as {@link #parse} would, so a
     * bracketed sub-list such as {@code "Chocolate (soy lecithin)"} still yields its children.
     * An empty or {@code null} list falls back to {@link #parse}, so a reader that found nothing
     * can never hide an allergen statement printed elsewhere on the package.
     */
    public static ParsedLabel fromIngredients(String rawText, List<String> extractedIngredients) {
        String raw = rawText == null ? "" : rawText;
        ParsedLabel parsed = parse(raw);
        if (extractedIngredients == null || extractedIngredients.isEmpty()) {
            return parsed;
        }
        List<IngredientItem> ingredients = new ArrayList<>();
        for (String ingredient : extractedIngredients) {
            if (ingredient != null) {
                addIngredients(ingredient, 0, ingredients);
            }
        }
        return new ParsedLabel(raw, parsed.ingredientHeadingFound(), ingredients, parsed.statements());
    }

    /** Joins hyphenated line wraps and flattens newlines so the label reads as running text. */
    private static String prepare(String raw) {
        String s = raw.replace("\r", "");
        s = s.replaceAll("(?<=\\p{L})-[ \\t]*\\n[ \\t]*(?=\\p{Ll})", "");
        s = s.replaceAll("[ \\t]*\\n[ \\t]*", " ");
        return s.replace(BREAK, ' ').trim();
    }

    /**
     * Splits running text into sentence-like segments at depth-0 full stops and at the start of any
     * statement phrase. Brackets are respected so "(may contain milk)" stays inside its ingredient.
     */
    private static List<String> segments(String text) {
        String marked = STATEMENT_START.matcher(text).replaceAll(BREAK + "$0");
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < marked.length(); i++) {
            char c = marked.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            }
            boolean decimalPoint = c == '.' && i > 0 && i + 1 < marked.length()
                    && Character.isDigit(marked.charAt(i - 1)) && Character.isDigit(marked.charAt(i + 1));
            if (depth == 0 && (c == BREAK || (c == '.' && !decimalPoint))) {
                flush(current, out);
            } else if (c != BREAK) {
                current.append(c);
            }
        }
        flush(current, out);
        return out;
    }

    private static void flush(StringBuilder current, List<String> out) {
        String s = current.toString().trim();
        current.setLength(0);
        if (!s.isEmpty()) {
            out.add(s);
        }
    }

    /** Returns the segment as a statement, or {@code null} if it is ordinary ingredient text. */
    private static ParsedLabel.Statement asStatement(String segment) {
        String stripped = ALLERGEN_PREFIX.matcher(segment).replaceFirst("");
        String norm = TextUtil.normalise(stripped);
        if (norm.isEmpty()) {
            return null;
        }
        String text = stripped.trim().replaceAll("^[\\s:;,\\-–]+|[\\s;,]+$", "");
        if (MAY_CONTAIN.matcher(norm).matches()) {
            return new ParsedLabel.Statement(ParsedLabel.Kind.MAY_CONTAIN, text);
        }
        if (CONTAINS.matcher(norm).matches()) {
            return new ParsedLabel.Statement(ParsedLabel.Kind.CONTAINS, text);
        }
        return null;
    }

    /** Splits {@code text} at depth-0 commas/semicolons/bullets, recursing into bracketed sub-lists. */
    private static void addIngredients(String text, int depth, List<IngredientItem> out) {
        String cleaned = ALLERGEN_PREFIX.matcher(text).replaceFirst("");
        for (String part : splitTopLevel(cleaned)) {
            String original = part.trim().replaceAll("^[\\s:;,\\-–•·*]+|[\\s:;,\\-–•·*]+$", "");
            if (TextUtil.letterCount(original) < 2) {
                continue;
            }
            int open = firstOpen(original);
            if (open < 0) {
                out.add(new IngredientItem(original, original, depth));
                continue;
            }
            int close = matchingClose(original, open);
            String name = original.substring(0, open);
            String inner = close < 0 ? original.substring(open + 1) : original.substring(open + 1, close);
            String rest = close < 0 ? "" : original.substring(close + 1);
            out.add(new IngredientItem(original, (name + " " + rest).trim(), depth));
            addIngredients(inner, depth + 1, out);
        }
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            }
            if (depth == 0 && (c == ',' || c == ';' || c == '•' || c == '·')) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static int firstOpen(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '(' || s.charAt(i) == '[') {
                return i;
            }
        }
        return -1;
    }

    /** Index of the bracket closing the one at {@code open}, or -1 if OCR dropped it. */
    private static int matchingClose(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
