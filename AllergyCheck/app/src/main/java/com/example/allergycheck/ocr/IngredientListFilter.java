package com.example.allergycheck.ocr;

import java.util.Locale;

/**
 * Keeps only the part of a label that describes what is in the product: the ingredients
 * declaration plus any "contains" / "may contain" statement.
 *
 * <p>Not wired in yet — the test harness still runs {@link PassThroughFilter}
 * so that raw OCR output stays visible while the recogniser is being tuned. This class is the
 * landing spot for that logic; see the TODOs below. Until they are filled in, {@link #apply} is a
 * pass-through, so switching to this filter is safe at any point.
 *
 * <p>Switch it on with:
 * <pre>{@code new OcrPipeline(new IngredientListFilter())}</pre>
 */
public final class IngredientListFilter implements TextFilter {

    /** Line prefixes that open an ingredients declaration. Lower-case, accent-bearing forms included. */
    static final String[] INGREDIENT_HEADINGS = {
            "ingredient",   // "Ingredients:", "INGREDIENTS", "Ingredient list"
            "ingrédient", // fr-CA, mandatory on bilingual Canadian labels
            "ingrediente",
    };

    /** Line prefixes that close it — anything after these is a different panel. */
    static final String[] TERMINATORS = {
            "nutrition",
            "nutritional information",
            "valeur nutritive",
            "distributed by",
            "manufactured by",
            "best before",
            "keep refrigerated",
            "store in",
            "net wt",
            "produce of",
    };

    /** Precautionary statements. Worth keeping even when they sit outside the ingredients panel. */
    static final String[] ALLERGEN_STATEMENTS = {
            "contains",
            "may contain",
            "produced in a facility",
            "peut contenir",
            "contient",
    };

    @Override
    public OcrResult apply(OcrResult input) {
        // TODO(ingredients): replace the pass-through below with the following pass:
        //   1. Normalise each line with normalise() and find the first index whose text starts with
        //      one of INGREDIENT_HEADINGS. If there is none, fall through to step 4 — a cropped
        //      photo of just the panel often has no heading at all.
        //   2. Walk forward from that index, keeping lines, and stop at the first line that either
        //      starts with a TERMINATORS entry, or whose OcrLine.boundingBox() jumps far enough in
        //      x or y to be a different column/panel (compare against the median line height so the
        //      threshold scales with image resolution), or whose OcrLine.angle() differs by more
        //      than ~10 degrees from the heading line.
        //   3. Separately collect every line starting with an ALLERGEN_STATEMENTS entry, wherever it
        //      appears, and append it after the kept panel lines. The README requires these be
        //      distinguishable from ingredient evidence, so consider returning them as a second
        //      list (or tagging OcrLine) rather than merging blindly.
        //   4. If nothing was kept, return the input unchanged rather than an empty result — an
        //      empty result reads as "no allergens on this label", which is exactly the failure
        //      mode the README says never to produce. Report uncertainty instead.
        return input;
    }

    /** Lower-cases and strips leading punctuation/whitespace so headings match regardless of styling. */
    static String normalise(String text) {
        return text.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("^[^\\p{L}\\p{N}]+", "");
    }

    static boolean startsWithAny(String normalisedText, String[] prefixes) {
        for (String prefix : prefixes) {
            if (normalisedText.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
