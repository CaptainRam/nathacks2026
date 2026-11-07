package com.example.allergycheck.ingredients;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the ingredient list out of a label's OCR text — and does nothing else.
 *
 * <p>No allergen matching, no interpretation: it finds the "Ingredients" heading, takes the list
 * that follows, splits it into items exactly as written, and groups them into main ingredients and
 * additives / chemicals. If there is no heading it returns nothing rather than guessing which text
 * might be ingredients.
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

    /**
     * The label's own split between main ingredients and minor ones: "Contains 2% or less of:",
     * "and less than 1% of", "2% or less of each of the following:" …
     */
    private static final Pattern MINOR_MARKER = Pattern.compile(
            "(?iu)\\b(?:and\\s+)?(?:contains?\\s+)?"
                    + "(?:less\\s+than\\s+\\d+(?:[.,]\\d+)?\\s*%|\\d+(?:[.,]\\d+)?\\s*%\\s+or\\s+less)"
                    + "\\s+of(?:\\s+each\\s+of)?(?:\\s+the\\s+following)?\\s*:?");

    /**
     * Item names that identify an additive or specialised chemical. Matched only against the part
     * of an item outside brackets, so "Enriched flour (niacin, reduced iron)" stays an ingredient.
     */
    private static final Pattern ADDITIVE = Pattern.compile(
            "(?iu)\\b(?:"
                    // E / INS numbers: E330, E 471, INS 322
                    + "(?:e|ins)\\s?\\d{3,4}[a-z]?"
                    // mineral salts and acids: sodium acetate, calcium propionate, citric acid …
                    + "|sodium|potassium|calcium|magnesium|ammonium|iron|zinc"
                    + "|\\w*(?:phosphate|sulfate|sulphate|sulfite|sulphite|nitrite|nitrate|carbonate|chloride"
                    + "|hydroxide|oxide|benzoate|sorbate|propionate|acetate|lactate|citrate|glutamate)s?"
                    + "|acids?"
                    // named additives
                    + "|mono\\s*-?\\s*(?:and\\s+)?di\\s*-?\\s*glycerides|diglycerides|lecithin"
                    + "|(?:xanthan|guar|gellan|locust\\s+bean|cellulose|acacia|arabic)\\s+gum|carrageenan|pectin"
                    + "|maltodextrin|polysorbate|bha|bht|tbhq|msg|dextrose"
                    + "|modified\\s+(?:food\\s+|corn\\s+|tapioca\\s+|potato\\s+)?starch"
                    // functional classes: "Preservative (sodium benzoate)", "Colour (caramel)"
                    + "|preservatives?|emulsifiers?|stabili[sz]ers?|thickeners?|antioxidants?"
                    + "|acidity\\s+regulators?|raising\\s+agents?|leavening|colou?rs?|colou?ring"
                    + "|artificial\\s+flavou?rs?|sweeteners?"
                    // fortification vitamins
                    + "|niacin|thiamine?|riboflavin|vitamins?\\s+[a-k]\\d*|vitamins"
                    + ")\\b");

    /** A whole item that is just an additive code: "E330", "E 471", "INS 322". */
    private static final Pattern E_NUMBER = Pattern.compile("(?iu)(?:e|ins)\\s?\\d{3,4}[a-z]?");

    private IngredientExtractor() {
    }

    /**
     * Returns the ingredients in label order, split into main ingredients and additives /
     * chemicals, each trimmed but otherwise as written (a bracketed sub-list stays attached to its
     * parent). Empty when no ingredient heading is found.
     *
     * <p>If the label marks its own split ("Contains 2% or less of:"), that is used: everything
     * after the marker is an additive. Otherwise each item is placed by its name.
     */
    public static IngredientList extract(String rawText) {
        if (rawText == null) {
            return IngredientList.empty();
        }
        String text = prepare(rawText);

        int bodyStart = findHeadingEnd(text);
        if (bodyStart < 0) {
            return IngredientList.empty();
        }
        String body = text.substring(bodyStart);

        int end = body.length();
        end = Math.min(end, firstAtDepthZero(HEADING, body));       // a second-language list
        end = Math.min(end, firstAtDepthZero(TERMINATOR, body));
        end = Math.min(end, firstAtDepthZero(STATEMENT_START, body));
        end = Math.min(end, firstFullStop(body));
        body = body.substring(0, end);

        // 1. The label's own split, if it has one.
        Matcher marker = MINOR_MARKER.matcher(body);
        while (marker.find()) {
            if (depthAt(body, marker.start()) == 0) {
                return new IngredientList(items(body.substring(0, marker.start())),
                        items(body.substring(marker.end())));
            }
        }

        // 2. Otherwise, place each item by its name.
        List<String> ingredients = new ArrayList<>();
        List<String> additives = new ArrayList<>();
        for (String item : items(body)) {
            if (isAdditive(item)) {
                additives.add(item);
            } else {
                ingredients.add(item);
            }
        }
        return new IngredientList(ingredients, additives);
    }

    /**
     * Splits {@code text} into trimmed items, dropping OCR fragments with fewer than two letters —
     * except E/INS numbers such as "E330", which are real items with only one letter.
     */
    private static List<String> items(String text) {
        List<String> items = new ArrayList<>();
        for (String part : splitTopLevel(text)) {
            String item = part.trim().replaceAll("^[\\s:;,\\-–•·*]+|[\\s:;,\\-–•·*.]+$", "");
            if (letterCount(item) >= 2 || E_NUMBER.matcher(item).matches()) {
                items.add(item);
            }
        }
        return items;
    }

    /** Whether the item's own name (the part outside any brackets) names an additive or chemical. */
    private static boolean isAdditive(String item) {
        int bracket = item.length();
        for (int i = 0; i < item.length(); i++) {
            if (item.charAt(i) == '(' || item.charAt(i) == '[') {
                bracket = i;
                break;
            }
        }
        return ADDITIVE.matcher(item.substring(0, bracket)).find();
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
