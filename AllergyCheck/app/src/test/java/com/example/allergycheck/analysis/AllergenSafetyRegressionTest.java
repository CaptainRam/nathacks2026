package com.example.allergycheck.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.allergycheck.ingredients.IngredientExtractor;

import org.junit.Test;

import java.util.Arrays;

/** Regressions for punctuation-sensitive free claims and conservative OCR typo recovery. */
public class AllergenSafetyRegressionTest {

    private static final AllergenDictionary DICT = TestDictionaries.shipped();

    private static AnalysisReport analyse(String text, String allergenId) {
        return Analyzer.analyse(IngredientExtractor.extract(text), text, DICT,
                Arrays.asList(DICT.byId(allergenId)));
    }

    @Test
    public void freeRangeEggAfterCommaDoesNotNegateTheMilkDeclaration() {
        String text = "Ingredients: sugar, salt. Contains: Wheat, Milk, Free Range Egg.";
        assertEquals(AnalysisReport.Status.CONTAINS, analyse(text, "MILK").overall());
        assertEquals(AnalysisReport.Status.CONTAINS, analyse(text, "EGGS").overall());
    }

    @Test
    public void milkWithRnOcrConfusionIsFlaggedForReview() {
        assertUncertainIngredient("rnilk powder", "MILK");
    }

    @Test
    public void wheyWithOneMisreadLetterIsFlaggedForReview() {
        assertUncertainIngredient("wbey", "MILK");
    }

    @Test
    public void soyWithZeroOcrConfusionIsFlaggedForReview() {
        assertUncertainIngredient("s0y", "SOY");
    }

    @Test
    public void eggWithOneMisreadLetterIsFlaggedForReview() {
        assertUncertainIngredient("egq", "EGGS");
    }

    @Test
    public void fishWithOneOcrConfusionIsFlaggedForReview() {
        assertUncertainIngredient("f1sh", "FISH");
    }

    @Test
    public void listSeparatorsKeepFreeRangeEggSeparateFromTheMilkDeclaration() {
        for (String separator : new String[]{", ", "; ", " / ", " • ", " — ", " – ", " − ", " - "}) {
            String text = "Ingredients: sugar, salt. Contains: Milk" + separator + "Free Range Egg.";
            assertEquals(text, AnalysisReport.Status.CONTAINS, analyse(text, "MILK").overall());
            assertEquals(text, AnalysisReport.Status.CONTAINS, analyse(text, "EGGS").overall());
        }
    }

    @Test
    public void anExclusionCannotJoinSeparateDeclaredIngredients() {
        for (String text : new String[]{"Contains: cocoa, butter.", "Contains: oat, milk."}) {
            assertEquals(text, AnalysisReport.Status.CONTAINS, analyse(text, "MILK").overall());
        }
    }

    @Test
    public void visualConfusionsAreHandledInOtherWordsAndPluralForms() {
        String[][] cases = {
                {"mi1k", "MILK"}, {"mllk", "MILK"}, {"rnilks", "MILK"},
                {"wbeys", "MILK"}, {"egqs", "EGGS"}, {"f1shes", "FISH"},
                {"crearn", "MILK"}, {"alrnond", "TREE_NUTS"}
        };
        for (String[] row : cases) {
            assertUncertainIngredient(row[0], row[1]);
        }
    }

    @Test
    public void shortAllergenWordsSplitByOcrAreFlaggedForReview() {
        String[][] cases = {
                {"mi lk", "MILK"}, {"wh ey", "MILK"}, {"s oy", "SOY"},
                {"eg g", "EGGS"}, {"fi sh", "FISH"}
        };
        for (String[] row : cases) {
            assertUncertainIngredient(row[0], row[1]);
        }
    }

    @Test
    public void punctuationDoesNotRejoinSeparateDeclarationsIntoAnAllergen() {
        String[][] cases = {{"mi, lk", "MILK"}, {"pean, ut", "PEANUTS"}};
        for (String[] row : cases) {
            assertEquals(row[0], AnalysisReport.Status.NOT_DETECTED,
                    analyse("Ingredients: sugar, salt. Contains: " + row[0] + ".", row[1]).overall());
        }
    }

    @Test
    public void ordinaryShortWordsDoNotBecomeAllergenTypos() {
        String[][] cases = {
                {"silk", "MILK"}, {"they", "MILK"}, {"dish", "FISH"},
                {"say", "SOY"}, {"cold", "FISH"}, {"bake", "FISH"},
                {"bakes", "FISH"}, {"age", "EGGS"}, {"so you", "SOY"}
        };
        for (String[] row : cases) {
            assertEquals(row[0], AnalysisReport.Status.NOT_DETECTED,
                    analyse("Ingredients: sugar, " + row[0] + ", salt.", row[1]).overall());
        }
    }

    @Test
    public void explicitFreeClaimsDoNotBecomeFuzzyFindings() {
        String[][] cases = {
                {"milk-free", "MILK"}, {"casein free", "MILK"},
                {"peanut free", "PEANUTS"}, {"gluten-free", "WHEAT"},
                {"rnilk-free", "MILK"}, {"wbey free", "MILK"},
                {"f1sh-free", "FISH"}, {"mi lk free", "MILK"}
        };
        for (String[] row : cases) {
            assertEquals(row[0], AnalysisReport.Status.NOT_DETECTED,
                    analyse("Ingredients: sugar, " + row[0] + ", salt.", row[1]).overall());
        }
    }

    @Test
    public void aFreeClaimDoesNotHideALaterPositiveDeclaration() {
        for (String id : new String[]{"MILK", "PEANUTS"}) {
            String name = DICT.byId(id).label();
            String text = "Ingredients: sugar, salt. Contains: " + name + "-free, " + name + ".";
            assertEquals(text, AnalysisReport.Status.CONTAINS, analyse(text, id).overall());
        }
    }

    @Test
    public void attachedUnicodeDashesKeepMultiwordAllergenTermsTogether() {
        String[][] cases = {
                {"tree–nut", "TREE_NUTS"}, {"ground—nut oil", "PEANUTS"},
                {"pine‑nut oil", "TREE_NUTS"}, {"brazil−nut", "TREE_NUTS"}
        };
        for (String[] row : cases) {
            assertEquals(row[0], AnalysisReport.Status.CONTAINS,
                    analyse("Ingredients: sugar, " + row[0] + ", salt.", row[1]).overall());
        }
    }

    @Test
    public void wrappedUnicodeDashesKeepMultiwordAllergenTermsTogether() {
        String[][] cases = {
                {"TREE–\nNUT", "TREE_NUTS"}, {"ground—\nnut oil", "PEANUTS"},
                {"PINE‑\nNUT OIL", "TREE_NUTS"}, {"MILK–\nFAT", "MILK"}
        };
        for (String[] row : cases) {
            assertEquals(row[0], AnalysisReport.Status.CONTAINS,
                    analyse("Ingredients: sugar, " + row[0] + ", salt.", row[1]).overall());
        }
    }

    @Test
    public void unicodeDashesKeepAttachedAndWrappedFreeClaimsTogether() {
        for (String dash : new String[]{"‐", "‑", "‒", "–", "—", "−", "﹣", "－"}) {
            for (String wrap : new String[]{"", "\n"}) {
                String milk = "Ingredients: sugar, milk" + dash + wrap + "free, salt.";
                String gluten = "Ingredients: sugar, GLUTEN" + dash + wrap + "FREE, salt.";
                assertEquals(milk, AnalysisReport.Status.NOT_DETECTED, analyse(milk, "MILK").overall());
                assertEquals(gluten, AnalysisReport.Status.NOT_DETECTED,
                        analyse(gluten, "WHEAT").overall());
            }
        }
    }

    @Test
    public void misreadAllergenInAWarningRetainsItsSourceAndOriginalEvidence() {
        AnalysisReport report = analyse(
                "Ingredients: sugar, salt. May contain rnilk.", "MILK");
        assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
        Finding finding = report.results().get(0).findings().get(0);
        assertEquals(Finding.Source.MAY_CONTAIN_STATEMENT, finding.source());
        assertEquals(Finding.Reason.UNCERTAIN_READ, finding.reason());
        assertTrue(finding.evidence().contains("rnilk"));
        assertEquals("milk", finding.matchedTerm());
    }

    private static void assertUncertainIngredient(String ingredient, String allergenId) {
        AnalysisReport report = analyse("Ingredients: sugar, " + ingredient + ", salt.", allergenId);
        assertTrue("misread allergen must be detected or flagged for review",
                report.overall() == AnalysisReport.Status.POSSIBLE
                        || report.overall() == AnalysisReport.Status.CONTAINS);
        assertFalse(report.results().get(0).findings().isEmpty());
        if (report.overall() == AnalysisReport.Status.POSSIBLE) {
            assertEquals(Finding.Reason.UNCERTAIN_READ,
                    report.results().get(0).findings().get(0).reason());
        }
    }
}
