package com.example.allergycheck.ingredients;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the ingredient list out of a label's OCR text — and does nothing else.
 *
 * <p>No allergen matching, no interpretation: it finds the "Ingredients" heading, takes the list
 * that follows, and splits it into items exactly as written. If there is no heading it returns an
 * empty list rather than guessing which text might be ingredients.
 *
 * <p>Pure Java with no Android dependencies, so it is unit-testable without a device.
 */
public final class IngredientExtractor {

    /** "Ingredients:", "INGREDIENTS", "Ingrédients :", "Ingredient list -", "Ingredientes". */
    private static final Pattern HEADING = Pattern.compile(
            "(?iu)\\b(?:ingr[eé]dients?|ingredientes)\\b(?:\\s+list)?(\\s*[:\\-–])?");

    /** Phrases that start a different panel of the package; the list ends before them. */
    private static final Pattern TERMINATOR = Pattern.compile(
            "(?iu)\\b(?:nutrition(?:al)? (?:facts|information)|valeur nutritive|distributed by|distribu[eé] par"
                    + "|manufactured (?:by|for)|best before|meilleur avant|net w(?:eigh)?t|keep refrigerated"
                    + "|store in)\\b");

    /**
     * Allergen statements that often follow the list without a full stop ("…, salt Contains: milk").
     * They are not ingredients, so the list ends before them. "Contains 2% or less of:" has no colon
     * straight after "contains" and so does not match — it is part of the list.
     */
    private static final Pattern STATEMENT_START = Pattern.compile(
            "(?iu)\\b(?:may contain|might contain|peut contenir|contains?\\s*:|contient\\s*:"
                    + "|(?:produced|made|manufactured|processed|packaged|packed)\\s+(?:in|on)\\s+(?:a|an|the)?\\s*"
                    + "(?:facility|plant|factory|equipment|line))");

    private IngredientExtractor() {
    }

    /**
     * Returns the ingredients in label order, each trimmed but otherwise as written (a bracketed
     * sub-list stays attached to its parent). Empty when no ingredient heading is found.
     */
    public static List<String> extract(String rawText) {
        if (rawText == null) {
            return Collections.emptyList();
        }
        String text = prepare(rawText);

        int bodyStart = findHeadingEnd(text);
        if (bodyStart < 0) {
            return Collections.emptyList();
        }
        String body = text.substring(bodyStart);

        int end = body.length();
        end = Math.min(end, firstAtDepthZero(HEADING, body));       // a second-language list
        end = Math.min(end, firstAtDepthZero(TERMINATOR, body));
        end = Math.min(end, firstAtDepthZero(STATEMENT_START, body));
        end = Math.min(end, firstFullStop(body));
        body = body.substring(0, end);

        List<String> items = new ArrayList<>();
        for (String part : splitTopLevel(body)) {
            String item = part.trim().replaceAll("^[\\s:;,\\-–•·*]+|[\\s:;,\\-–•·*.]+$", "");
            if (letterCount(item) >= 2) {
                items.add(item);
            }
        }
        return items;
    }

    /** Joins hyphenated line wraps ("pea-\nnut" → "peanut") and flattens newlines to spaces. */
    private static String prepare(String raw) {
        String s = raw.replace("\r", "");
        s = s.replaceAll("(?<=\\p{L})-[ \\t]*\\n[ \\t]*(?=\\p{Ll})", "");
        s = s.replaceAll("[ \\t]*\\n[ \\t]*", " ");
        return s.trim();
    }

    /**
     * End index of the heading that introduces the list, or -1. A heading followed by ":" or "-" is
     * preferred, so marketing text such as "no artificial ingredients" earlier on the package does
     * not win over the real "INGREDIENTS:" heading.
     */
    private static int findHeadingEnd(String text) {
        Matcher m = HEADING.matcher(text);
        int firstEnd = -1;
        while (m.find()) {
            if (m.group(1) != null) {
                return m.end();
            }
            if (firstEnd < 0) {
                firstEnd = m.end();
            }
        }
        return firstEnd;
    }

    /** Start of the first match outside brackets, or {@code text.length()} if none. */
    private static int firstAtDepthZero(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (m.start() > 0 && depthAt(text, m.start()) == 0) {
                return m.start();
            }
        }
        return text.length();
    }

    /** Index of the first full stop outside brackets that is not a decimal point, or the length. */
    private static int firstFullStop(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (c == '.' && depth == 0) {
                boolean decimal = i > 0 && i + 1 < text.length()
                        && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1));
                if (!decimal) {
                    return i;
                }
            }
        }
        return text.length();
    }

    private static int depthAt(String text, int index) {
        int depth = 0;
        for (int i = 0; i < index; i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            }
        }
        return depth;
    }

    /** Splits at commas, semicolons and bullets that are not inside brackets. */
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

    private static int letterCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
