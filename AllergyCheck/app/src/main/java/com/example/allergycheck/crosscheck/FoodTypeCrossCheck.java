package com.example.allergycheck.crosscheck;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A separate advisory. It cannot create findings or change the ingredient-label report. */
public final class FoodTypeCrossCheck implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String foodType;
    private final List<Allergen> commonAllergens;
    private final List<Allergen> missingAllergens;

    private FoodTypeCrossCheck(String foodType, List<Allergen> common, List<Allergen> missing) {
        this.foodType = foodType;
        this.commonAllergens = Collections.unmodifiableList(new ArrayList<>(common));
        this.missingAllergens = Collections.unmodifiableList(new ArrayList<>(missing));
    }

    public static FoodTypeCrossCheck compare(AnalysisReport report, FoodTypeEstimate estimate) {
        if (report == null || estimate == null
                || report.overall() == AnalysisReport.Status.UNREADABLE
                || report.overall() == AnalysisReport.Status.NO_ALLERGENS_SELECTED) {
            throw new IllegalArgumentException("A readable ingredient-label report is required");
        }
        Map<String, AllergenResult> selected = new HashMap<>();
        for (AllergenResult result : report.results()) selected.put(result.allergen().id(), result);
        List<Allergen> common = new ArrayList<>();
        List<Allergen> missing = new ArrayList<>();
        for (Allergen allergen : estimate.commonAllergens()) {
            AllergenResult result = selected.get(allergen.id());
            if (result == null) {
                // A changed selection or an unvalidated model ID must never broaden the report.
                throw new IllegalArgumentException("The estimate contains an unselected allergen");
            }
            common.add(result.allergen());
            // Precautionary and possible findings already call for review in the label report.
            if (result.status() == AnalysisReport.Status.NOT_DETECTED) missing.add(result.allergen());
        }
        return new FoodTypeCrossCheck(estimate.foodType(), common, missing);
    }

    public String foodType() { return foodType; }
    public List<Allergen> commonAllergens() { return commonAllergens; }

    /** Common for the estimated category but not identified by the ingredient-label analysis. */
    public List<Allergen> missingAllergens() { return missingAllergens; }
}
