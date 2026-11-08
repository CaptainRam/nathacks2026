package com.example.allergycheck.ingredients;

import com.example.allergycheck.analysis.LabelSyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pulls the first English ingredient list out of a label's OCR text, without allergen matching.
 * Uncertain punctuation is retained; recognized warnings and package panels end the list.
 * Pure Java so the same structural rules are used by the fallback parser and JVM tests.
 */
public final class IngredientExtractor {
    private IngredientExtractor() { }

    public static List<String> extract(String rawText) {
        if (rawText == null) return Collections.emptyList();
        String text = LabelSyntax.prepare(rawText);
        LabelSyntax.Heading heading = LabelSyntax.findHeading(text);
        if (heading == null) return Collections.emptyList();

        List<String> ingredients = new ArrayList<>();
        for (String part : LabelSyntax.splitItems(LabelSyntax.ingredientBody(text, heading))) {
            String item = part.trim().replaceAll("^[\\s:;,\\-–•·*]+|[\\s:;,\\-–•·*.]+$", "");
            if (LabelSyntax.isUsableIngredient(item)) ingredients.add(item);
        }
        return ingredients;
    }
}
