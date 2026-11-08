package com.example.allergycheck.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.allergycheck.ingredients.IngredientExtractor;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The home screen's path: OCR text → {@link IngredientExtractor} → {@link Analyzer}. Checks that
 * analysing the extracted list gives the same verdicts as analysing the raw text, and that the
 * statements printed outside the list are still taken into account.
 */
public class ExtractedIngredientsAnalysisTest {

    private static final AllergenDictionary DICT = TestDictionaries.shipped();

    private static Allergen a(String id) {
        Allergen allergen = DICT.byId(id);
        if (allergen == null) {
            throw new AssertionError("no allergen " + id + " in the shipped dictionary");
        }
        return allergen;
    }

    private static AnalysisReport analyseExtracted(String text, Allergen... allergens) {
        return Analyzer.analyse(IngredientExtractor.extract(text), text, DICT,
                Arrays.asList(allergens));
    }

    @Test
    public void findsAllergenInExtractedIngredients() {
        String label = "INGREDIENTS: Wheat flour, sugar, whey powder, salt.";
        AnalysisReport report = analyseExtracted(label, a("MILK"), a("WHEAT"));

        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertEquals(2, report.results().size());
        for (AllergenResult result : report.results()) {
            assertEquals(AnalysisReport.Status.CONTAINS, result.status());
            assertFalse(result.findings().isEmpty());
            assertEquals(Finding.Source.INGREDIENT, result.findings().get(0).source());
        }
    }

    @Test
    public void matchesTheVerdictFromTheRawText() {
        String label = "INGREDIENTS: Sugar, chocolate (cocoa butter, soy lecithin), salt. "
                + "May contain peanuts.";
        List<Allergen> allergens = Arrays.asList(a("SOY"), a("PEANUTS"), a("MILK"));

        AnalysisReport fromText = Analyzer.analyse(label, DICT, allergens);
        AnalysisReport fromExtraction = analyseExtracted(label, a("SOY"), a("PEANUTS"),
                a("MILK"));

        assertEquals(fromText.overall(), fromExtraction.overall());
        assertEquals(fromText.results().size(), fromExtraction.results().size());
        for (int i = 0; i < fromText.results().size(); i++) {
            assertEquals(fromText.results().get(i).allergen(),
                    fromExtraction.results().get(i).allergen());
            assertEquals(fromText.results().get(i).status(),
                    fromExtraction.results().get(i).status());
        }
    }

    @Test
    public void keepsSubIngredientsFromBracketedItems() {
        String label = "INGREDIENTS: Sugar, chocolate (cocoa butter, soy lecithin).";
        AnalysisReport report = analyseExtracted(label, a("SOY"));

        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertEquals("soy lecithin",
                report.results().get(0).findings().get(0).evidence().toLowerCase());
        // "cocoa butter" is an exclusion, so dairy is never asserted; "chocolate" is ambiguous,
        // which is only ever a "possible".
        AnalysisReport milk = analyseExtracted(label, a("MILK"));
        assertEquals(AnalysisReport.Status.POSSIBLE, milk.overall());
        assertEquals(Finding.Reason.AMBIGUOUS_INGREDIENT,
                milk.results().get(0).findings().get(0).reason());
    }

    @Test
    public void precautionaryStatementOutsideTheListStillCounts() {
        String label = "INGREDIENTS: Oats, sugar. May contain traces of almonds.";
        AnalysisReport report = analyseExtracted(label, a("TREE_NUTS"));

        assertEquals(AnalysisReport.Status.MAY_CONTAIN, report.overall());
        assertEquals(Finding.Source.MAY_CONTAIN_STATEMENT,
                report.results().get(0).findings().get(0).source());
    }

    @Test
    public void containsStatementIsUsedWhenNoIngredientListWasExtracted() {
        // A tight crop of the allergen panel: the extractor finds no list at all.
        String label = "CONTAINS: MILK AND SOY.";
        assertTrue(IngredientExtractor.extract(label).isEmpty());

        AnalysisReport report = analyseExtracted(label, a("MILK"));
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertEquals(Finding.Source.CONTAINS_STATEMENT,
                report.results().get(0).findings().get(0).source());
    }

    @Test
    public void unreadableLabelIsNeverReportedAsClear() {
        AnalysisReport report = analyseExtracted("ING", a("MILK"));

        assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
        assertTrue(report.lowConfidence());
    }

    @Test
    public void noAllergensSelectedIsItsOwnVerdict() {
        AnalysisReport report = Analyzer.analyse(
                IngredientExtractor.extract("INGREDIENTS: Wheat flour, salt."),
                "INGREDIENTS: Wheat flour, salt.", DICT, Collections.<Allergen>emptyList());

        assertEquals(AnalysisReport.Status.NO_ALLERGENS_SELECTED, report.overall());
        assertTrue(report.results().isEmpty());
    }

    @Test
    public void emptyExtractionFallsBackToParsingTheWholeText() {
        String label = "INGREDIENTS: Wheat flour, salt.";
        List<IngredientItem> ingredients =
                LabelParser.fromIngredients(label, Collections.<String>emptyList()).ingredients();

        assertEquals(LabelParser.parse(label).ingredients().size(), ingredients.size());
    }
}
