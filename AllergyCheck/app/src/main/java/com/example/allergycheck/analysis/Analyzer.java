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
        ParsedLabel label = LabelParser.parse(rawText);
        Set<Allergen> allergens = selected == null || selected.isEmpty()
                ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(selected);

        if (allergens.isEmpty()) {
            return new AnalysisReport(AnalysisReport.Status.NO_ALLERGENS_SELECTED,
                    Collections.<AllergenResult>emptyList(), label, false);
        }

        // Always look for evidence first: a short crop like "Contains: egg" is too little text to
        // clear anything, but is plenty to flag an allergen. Too little text only matters when
        // nothing was found — then it must read as "couldn't check", never as "not detected".
        boolean thin = label.isEmpty() || TextUtil.letterCount(label.rawText()) < MIN_LETTERS;
        List<AllergenResult> results = new ArrayList<>();
        for (Allergen allergen : allergens) {
            List<Finding> findings = AllergenMatcher.match(label, allergen);
            findings.sort((a, b) -> a.level().compareTo(b.level()));
            AnalysisReport.Status status = findings.isEmpty() && thin
                    ? AnalysisReport.Status.UNREADABLE : statusOf(findings);
            results.add(new AllergenResult(allergen, status, findings));
        }
        results.sort((a, b) -> {
            int byStatus = a.status().compareTo(b.status());
            return byStatus != 0 ? byStatus : a.allergen().compareTo(b.allergen());
        });

        // Statuses are ordered by severity, so the first result decides the overall banner.
        return new AnalysisReport(results.get(0).status(), results, label, thin || isLowConfidence(label));
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
