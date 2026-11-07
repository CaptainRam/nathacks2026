package com.example.allergycheck.analysis;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.allergycheck.ocr.OcrResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point for label analysis: raw OCR text + the user's allergens → an {@link AnalysisReport}.
 *
 * <p>All verdicts come from deterministic rules ({@link LabelParser}, {@link AllergenMatcher}).
 * Nothing here — and nothing that may later be layered on top, such as an LLM explainer — can
 * turn an unreadable or empty label into "no allergens found".
 */
public final class Analyzer {

    /** Below this many letters there is not enough text to say anything about a label. */
    static final int MIN_LETTERS = 12;

    private Analyzer() {
    }

    public static AnalysisReport analyse(@NonNull OcrResult ocr, @Nullable Collection<Allergen> selected) {
        return analyse(ocr.fullText(), selected);
    }

    public static AnalysisReport analyse(@Nullable String rawText, @Nullable Collection<Allergen> selected) {
        return analyse(LabelParser.parse(rawText), selected);
    }

    /**
     * Analyses an ingredient list that has already been read out of {@code rawText} elsewhere —
     * see {@link LabelParser#fromIngredients}. The allergen statements still come from the full
     * text, so a "may contain" warning outside the list is not lost.
     */
    public static AnalysisReport analyse(@Nullable List<String> ingredients, @Nullable String rawText,
                                         @Nullable Collection<Allergen> selected) {
        return analyse(LabelParser.fromIngredients(rawText, ingredients), selected);
    }

    public static AnalysisReport analyse(@NonNull ParsedLabel label, @Nullable Collection<Allergen> selected) {
        Set<Allergen> allergens = selected == null || selected.isEmpty()
                ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(selected);

        if (allergens.isEmpty()) {
            return new AnalysisReport(AnalysisReport.Status.NO_ALLERGENS_SELECTED,
                    Collections.<AllergenResult>emptyList(), label, false);
        }

        boolean unreadable = label.isEmpty() || TextUtil.letterCount(label.rawText()) < MIN_LETTERS;
        List<AllergenResult> results = new ArrayList<>();
        for (Allergen allergen : allergens) {
            if (unreadable) {
                results.add(new AllergenResult(allergen, AnalysisReport.Status.UNREADABLE,
                        Collections.<Finding>emptyList()));
                continue;
            }
            List<Finding> findings = AllergenMatcher.match(label, allergen);
            findings.sort((a, b) -> a.level().compareTo(b.level()));
            results.add(new AllergenResult(allergen, statusOf(findings), findings));
        }
        results.sort((a, b) -> {
            int byStatus = a.status().compareTo(b.status());
            return byStatus != 0 ? byStatus : a.allergen().compareTo(b.allergen());
        });

        AnalysisReport.Status overall = unreadable ? AnalysisReport.Status.UNREADABLE : results.get(0).status();
        return new AnalysisReport(overall, results, label, unreadable || isLowConfidence(label));
    }

    private static AnalysisReport.Status statusOf(List<Finding> findings) {
        if (findings.isEmpty()) {
            return AnalysisReport.Status.NOT_DETECTED;
        }
        switch (findings.get(0).level()) {
            case CONTAINS:
                return AnalysisReport.Status.CONTAINS;
            case MAY_CONTAIN:
                return AnalysisReport.Status.MAY_CONTAIN;
            default:
                return AnalysisReport.Status.POSSIBLE;
        }
    }

    /** Thin evidence: no "Ingredients" heading and only a couple of items were found. */
    private static boolean isLowConfidence(ParsedLabel label) {
        return !label.ingredientHeadingFound() && label.ingredients().size() < 3;
    }
}
