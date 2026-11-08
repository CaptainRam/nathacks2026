package com.example.allergycheck.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.allergycheck.ingredients.IngredientExtractor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/** Missing vocabulary cases from docs/TEST_CASES.md, exercised through the home scan path. */
@RunWith(Parameterized.class)
public class AllergenVocabularyCoverageTest {

    private static final AllergenDictionary DICT = TestDictionaries.shipped();

    @Parameterized.Parameters(name = "{0}: {1} -> {2} {3}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][]{
                {"A-01", "casein", "MILK", AnalysisReport.Status.CONTAINS},
                {"A-01", "whey", "MILK", AnalysisReport.Status.CONTAINS},
                {"A-01", "milk solids", "MILK", AnalysisReport.Status.CONTAINS},
                {"A-01", "ghee", "MILK", AnalysisReport.Status.CONTAINS},
                {"A-02", "albumin", "EGGS", AnalysisReport.Status.CONTAINS},
                {"A-02", "ovalbumin", "EGGS", AnalysisReport.Status.CONTAINS},
                {"A-02", "mayonnaise", "EGGS", AnalysisReport.Status.CONTAINS},
                {"A-03", "semolina", "WHEAT", AnalysisReport.Status.CONTAINS},
                {"A-03", "spelt", "WHEAT", AnalysisReport.Status.CONTAINS},
                {"A-03", "durum", "WHEAT", AnalysisReport.Status.CONTAINS},
                {"A-03", "couscous", "WHEAT", AnalysisReport.Status.CONTAINS},
                {"A-04", "arachis oil", "PEANUTS", AnalysisReport.Status.CONTAINS},
                {"A-04", "groundnut", "PEANUTS", AnalysisReport.Status.CONTAINS},
                {"A-05", "tahini", "SESAME", AnalysisReport.Status.CONTAINS},
                {"A-05", "halva", "SESAME", AnalysisReport.Status.CONTAINS},
                {"A-07", "walnuts", "TREE_NUTS", AnalysisReport.Status.CONTAINS},
                {"A-07", "cashews", "TREE_NUTS", AnalysisReport.Status.CONTAINS},
                {"A-07", "anchovies", "FISH", AnalysisReport.Status.CONTAINS},
                {"A-07", "soybeans", "SOY", AnalysisReport.Status.CONTAINS},
                {"A-08", "organic whole milk", "MILK", AnalysisReport.Status.CONTAINS},
                {"A-16", "cream of tartar", "MILK", AnalysisReport.Status.NOT_DETECTED},
                {"A-16", "milk thistle", "MILK", AnalysisReport.Status.NOT_DETECTED},
                {"A-16", "water chestnut", "TREE_NUTS", AnalysisReport.Status.NOT_DETECTED},
                {"A-17", "nutmeg", "TREE_NUTS", AnalysisReport.Status.NOT_DETECTED},
                {"A-17", "butternut squash", "TREE_NUTS", AnalysisReport.Status.NOT_DETECTED},
                {"A-18", "rice flour", "WHEAT", AnalysisReport.Status.NOT_DETECTED},
                {"A-18", "corn starch", "WHEAT", AnalysisReport.Status.NOT_DETECTED},
                {"A-18", "potato starch", "WHEAT", AnalysisReport.Status.NOT_DETECTED},
                {"A-31", "peanut free facility", "PEANUTS", AnalysisReport.Status.NOT_DETECTED},
                {"A-32", "free-range eggs", "EGGS", AnalysisReport.Status.CONTAINS},
                {"A-33", "milk (free of antibiotics)", "MILK", AnalysisReport.Status.CONTAINS},
                {"A-36", "chocolate", "MILK", AnalysisReport.Status.POSSIBLE},
                {"A-36", "natural flavour", "MILK", AnalysisReport.Status.POSSIBLE},
                {"A-36", "caramel", "MILK", AnalysisReport.Status.POSSIBLE},
                // The shipped dictionary deliberately treats coconut as ambiguous, not a firm hit.
                {"A-37", "coconut", "TREE_NUTS", AnalysisReport.Status.POSSIBLE}
        });
    }

    private final String ingredient;
    private final String allergenId;
    private final AnalysisReport.Status expected;

    public AllergenVocabularyCoverageTest(String caseId, String ingredient, String allergenId,
                                          AnalysisReport.Status expected) {
        this.ingredient = ingredient;
        this.allergenId = allergenId;
        this.expected = expected;
    }

    @Test
    public void ingredientHasExpectedVerdictAndEvidence() {
        String text = "Ingredients: sugar, " + ingredient + ", salt.";
        Allergen allergen = DICT.byId(allergenId);
        AnalysisReport report = Analyzer.analyse(IngredientExtractor.extract(text), text, DICT,
                Collections.singletonList(allergen));

        assertEquals(expected, report.overall());
        assertEquals(1, report.results().size());
        AllergenResult result = report.results().get(0);
        assertEquals(allergen, result.allergen());
        assertEquals(expected, result.status());
        if (expected == AnalysisReport.Status.NOT_DETECTED) {
            assertTrue(result.findings().isEmpty());
        } else {
            assertFalse(result.findings().isEmpty());
            Finding finding = result.findings().get(0);
            assertEquals(Finding.Source.INGREDIENT, finding.source());
            assertEquals(expected == AnalysisReport.Status.POSSIBLE
                            ? Finding.Reason.AMBIGUOUS_INGREDIENT : Finding.Reason.NONE,
                    finding.reason());
            assertEquals(expected == AnalysisReport.Status.POSSIBLE
                            ? Finding.Level.POSSIBLE : Finding.Level.CONTAINS,
                    finding.level());
        }
    }
}
