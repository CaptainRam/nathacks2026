package com.example.allergycheck.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Arrays;

/** Dictionary consistency and cross-allergen exclusions missing from the analysis branch plan. */
public class AllergenDictionaryCoverageTest {

    private static final AllergenDictionary DICT = TestDictionaries.shipped();

    /** D-06: exact entries in both buckets would make that term impossible to detect. */
    @Test
    public void noShippedTermIsAlsoAnExactExclusionForTheSameAllergen() {
        for (Allergen allergen : DICT.allergens()) {
            for (String[] term : DICT.terms(allergen)) {
                for (String[] exclusion : DICT.exclusions(allergen)) {
                    assertFalse(allergen.id() + ": " + String.join(" ", term),
                            Arrays.equals(term, exclusion));
                }
            }
        }
    }

    /** D-07: an exclusion for one allergen must not mask another allergen in the same item. */
    @Test
    public void crossAllergenExclusionsDoNotSuppressTheIngredientTheyName() {
        String[][] cases = {
                {"peanut butter", "MILK", "PEANUTS"},
                {"almond butter", "MILK", "TREE_NUTS"},
                {"soy milk", "MILK", "SOY"},
                {"almond flour", "WHEAT", "TREE_NUTS"}
        };
        for (String[] row : cases) {
            Allergen excluded = DICT.byId(row[1]);
            Allergen present = DICT.byId(row[2]);
            AnalysisReport report = Analyzer.analyse("Ingredients: sugar, " + row[0] + ", salt.",
                    DICT, Arrays.asList(excluded, present));
            assertEquals(row[0], 2, report.results().size());
            assertEquals(row[0], present, report.results().get(0).allergen());
            assertEquals(row[0], AnalysisReport.Status.CONTAINS,
                    report.results().get(0).status());
            assertEquals(row[0], excluded, report.results().get(1).allergen());
            assertEquals(row[0], AnalysisReport.Status.NOT_DETECTED,
                    report.results().get(1).status());
        }
    }
}
