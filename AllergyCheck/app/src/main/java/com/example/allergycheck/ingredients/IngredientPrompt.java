package com.example.allergycheck.ingredients;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the instruction given to the on-device model and parses its reply.
 *
 * <p>The model's job is narrow: read imperfect OCR text, mentally fix misread words
 * ("raregridients" → "ingredients", "sug4r" → "sugar"), and list the ingredients one per line.
 * The reply format is deliberately simple — a dash list, or the single word NONE — because a
 * ~1B-parameter model follows that far more reliably than JSON.
 *
 * <p>Pure Java with no Android dependencies, so prompt and parser are unit-testable.
 */
public final class IngredientPrompt {

    /** The model's reply when the text contains no ingredient list. */
    static final String NONE = "NONE";

    /**
     * Upper bound on OCR text sent to the model, so prompt + reply fit the model's context
     * window (2048 tokens for the recommended Gemma 3 1B build). Labels are rarely longer.
     */
    static final int MAX_OCR_CHARS = 3000;

    private static final String INSTRUCTIONS = String.join("\n",
            "You read text from a photo of a food label. The text came from OCR, so some words may be",
            "misread: letters swapped, digits in place of letters, or words split apart. For example",
            "\"raregridients\" or \"lngredients\" means \"ingredients\", \"sug4r\" means \"sugar\",",
            "\"sodlum acetate\" means \"sodium acetate\", and \"pea nut\" means \"peanut\".",
            "",
            "Task: list every ingredient from the label's ingredient list.",
            "",
            "Rules:",
            "- Write one ingredient per line, starting with \"- \".",
            "- Correct misread words to the real ingredient name.",
            "- Keep the label's order.",
            "- Keep sub-ingredients in brackets with their ingredient, for example",
            "  \"- Chocolate (sugar, cocoa butter, soy lecithin)\".",
            "- Include additives and chemicals such as sodium acetate, citric acid or E330.",
            "- Leave out the heading, notes like \"Contains 2% or less of\", allergen warnings",
            "  (\"Contains: ...\", \"May contain ...\"), nutrition facts and all other text.",
            "- Never add an ingredient that is not in the text.",
            "- If there is no ingredient list in the text, reply with exactly: " + NONE,
            "",
            "Example",
            "Label text: Crunchy Bar 50g RAREGRIDIENTS: Oats, sug4r, palm 0il, Contains 2% or less of:",
            "salt, sodlum bicarbonate. May contain peanuts. Nutrition Facts Calories 210",
            "Reply:",
            "- Oats",
            "- sugar",
            "- palm oil",
            "- salt",
            "- sodium bicarbonate",
            "",
            "Now do the same for this label.");

    /** A list line: "- x", "* x", "• x", "1. x", "2) x". */
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*•·]|\\d{1,3}[.)])\\s+(.+)$");

    private IngredientPrompt() {
    }

    /** The full request for one label. */
    public static String build(String ocrText) {
        String text = ocrText == null ? "" : ocrText.trim();
        if (text.length() > MAX_OCR_CHARS) {
            text = text.substring(0, MAX_OCR_CHARS);
        }
        return INSTRUCTIONS + "\nLabel text: " + text + "\nReply:";
    }

    /**
     * Turns the model's reply into ingredients.
     *
     * @return the ingredients in order; an empty list when the model answered NONE
     * @throws IllegalArgumentException when the reply is neither a list nor NONE — the model went
     *     off script, and that must be reported as a failure, not as "no ingredients"
     */
    public static List<String> parse(String reply) {
        if (reply == null || reply.trim().isEmpty()) {
            throw new IllegalArgumentException("The model returned an empty reply");
        }
        List<String> items = new ArrayList<>();
        for (String line : reply.split("\\r?\\n")) {
            Matcher m = LIST_ITEM.matcher(line);
            if (!m.matches()) {
                continue; // chatter such as "Here are the ingredients:" is ignored
            }
            String item = clean(m.group(1));
            if (!item.isEmpty() && !item.equalsIgnoreCase(NONE) && !items.contains(item)) {
                items.add(item);
            }
        }
        if (!items.isEmpty()) {
            return items;
        }
        if (isNone(reply)) {
            return new ArrayList<>();
        }
        throw new IllegalArgumentException("The model's reply wasn't an ingredient list");
    }

    /** Strips markdown emphasis and stray punctuation the model may wrap an item in. */
    private static String clean(String item) {
        return item.replace("**", "").replace("`", "")
                .replaceAll("^[\\s\"'*_]+|[\\s\"'*_,;.]+$", "")
                .trim();
    }

    /** True for "NONE", "None.", "**NONE**" and the like, possibly after a short preamble. */
    private static boolean isNone(String reply) {
        String normalised = reply.replaceAll("[^A-Za-z]+", " ").trim().toUpperCase(Locale.ROOT);
        return normalised.equals(NONE) || normalised.endsWith(" " + NONE);
    }
}
