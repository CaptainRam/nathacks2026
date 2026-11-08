package com.example.allergycheck.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AnalyzerTest {

    private static final AllergenDictionary DICT = TestDictionaries.shipped();

    /** The shipped dictionary's allergen with this id, e.g. {@code a("TREE_NUTS")}. */
    private static Allergen a(String id) {
        Allergen allergen = DICT.byId(id);
        if (allergen == null) {
            throw new AssertionError("no allergen " + id + " in the shipped dictionary");
        }
        return allergen;
    }

    private static AnalysisReport run(String text, Allergen... allergens) {
        return Analyzer.analyse(text, DICT, Arrays.asList(allergens));
    }

    private static AllergenResult resultFor(AnalysisReport report, Allergen allergen) {
        for (AllergenResult r : report.results()) {
            if (allergen.equals(r.allergen())) {
                return r;
            }
        }
        throw new AssertionError("no result for " + allergen);
    }

    // ---- parsing -----------------------------------------------------------------------------

    @Test
    public void parsesSimpleIngredientList() {
        ParsedLabel label = LabelParser.parse("INGREDIENTS: Sugar, Cocoa Butter, Salt.");
        assertTrue(label.ingredientHeadingFound());
        assertEquals(3, label.ingredients().size());
        assertEquals("Sugar", label.ingredients().get(0).original());
        assertEquals("Salt", label.ingredients().get(2).original());
    }

    @Test
    public void nestedBracketsBecomeParentAndChildren() {
        ParsedLabel label = LabelParser.parse(
                "Ingredients: Chocolate (sugar, cocoa butter, soy lecithin), wheat flour, salt");
        assertEquals(6, label.ingredients().size());
        assertEquals(0, label.ingredients().get(0).depth());
        assertEquals(1, label.ingredients().get(1).depth());
        assertEquals("soy lecithin", label.ingredients().get(3).original());
        assertEquals(0, label.ingredients().get(4).depth());
    }

    @Test
    public void unbalancedBracketStillKeepsContents() {
        ParsedLabel label = LabelParser.parse("Ingredients: Chocolate (sugar, milk powder, salt");
        boolean sawMilk = false;
        for (IngredientItem item : label.ingredients()) {
            sawMilk |= item.original().contains("milk powder");
        }
        assertTrue(sawMilk);
    }

    @Test
    public void statementsAreSeparatedFromIngredients() {
        ParsedLabel label = LabelParser.parse(
                "Ingredients: flour, sugar, salt. Contains: wheat, milk. May contain peanuts and sesame.");
        assertEquals(3, label.ingredients().size());
        assertEquals(2, label.statements().size());
        assertEquals(ParsedLabel.Kind.CONTAINS, label.statements().get(0).kind());
        assertEquals(ParsedLabel.Kind.MAY_CONTAIN, label.statements().get(1).kind());
    }

    @Test
    public void mayContainWithoutFullStopIsSplitOff() {
        ParsedLabel label = LabelParser.parse("Ingredients: flour, sugar, may contain peanuts");
        assertEquals(2, label.ingredients().size());
        assertEquals(1, label.statements().size());
        assertEquals(ParsedLabel.Kind.MAY_CONTAIN, label.statements().get(0).kind());
    }

    @Test
    public void containsTwoPercentOrLessIsAnIngredientNotAStatement() {
        ParsedLabel label = LabelParser.parse("Ingredients: corn, Contains 2% or less of: salt, soy lecithin.");
        assertTrue(label.statements().isEmpty());
        assertEquals(3, label.ingredients().size());
    }

    @Test
    public void hyphenatedLineWrapIsRejoined() {
        ParsedLabel label = LabelParser.parse("Ingredients: sugar, pea-\nnut oil, salt");
        assertEquals("peanut oil", label.ingredients().get(1).original());
    }

    @Test
    public void decimalPointDoesNotSplitIngredient() {
        ParsedLabel label = LabelParser.parse("Ingredients: water, salt 0.5%, sugar");
        assertEquals(3, label.ingredients().size());
    }

    @Test
    public void nutritionPanelAfterIngredientsIsDropped() {
        ParsedLabel label = LabelParser.parse(
                "Ingredients: rice, salt\nNutrition Facts Calories 120 Fat 2g\nDistributed by Acme");
        assertEquals(2, label.ingredients().size());
    }

    @Test
    public void containsStatementBeforeHeadingIsKept() {
        ParsedLabel label = LabelParser.parse("CONTAINS: MILK\nIngredients: sugar, cocoa");
        assertEquals(1, label.statements().size());
        assertEquals(2, label.ingredients().size());
    }

    @Test
    public void secondIngredientsHeadingDoesNotRestartTheList() {
        ParsedLabel label = LabelParser.parse("Ingredients: sugar, milk. Ingredients: cocoa, salt.");
        assertEquals(4, label.ingredients().size());
        assertEquals(AnalysisReport.Status.CONTAINS,
                run("Ingredients: sugar, milk. Ingredients: cocoa, salt.", a("MILK")).overall());
    }

    /** Matching is English-only by design; a French panel is kept as text but recognises nothing. */
    @Test
    public void frenchOnlyLabelIsNotAnalysed() {
        AnalysisReport report = run("Ingrédients : sucre, lait, beurre et fromage.", a("MILK"));
        assertNotEquals(AnalysisReport.Status.CONTAINS, report.overall());
    }

    // ---- matching ----------------------------------------------------------------------------

    @Test
    public void ingredientMatchIsContains() {
        AnalysisReport report = run("Ingredients: sugar, whey powder, salt", a("MILK"));
        AllergenResult milk = resultFor(report, a("MILK"));
        assertEquals(AnalysisReport.Status.CONTAINS, milk.status());
        assertEquals("whey", milk.findings().get(0).matchedTerm());
        assertEquals(Finding.Source.INGREDIENT, milk.findings().get(0).source());
    }

    @Test
    public void mayContainStatementIsMayContainNotContains() {
        AnalysisReport report = run(
                "Ingredients: sugar, cocoa, salt. May contain peanuts.", a("PEANUTS"));
        assertEquals(AnalysisReport.Status.MAY_CONTAIN, report.overall());
        assertEquals(Finding.Source.MAY_CONTAIN_STATEMENT, report.results().get(0).findings().get(0).source());
    }

    @Test
    public void facilityStatementCountsAsMayContain() {
        AnalysisReport report = run(
                "Ingredients: oats, sugar, salt. Produced in a facility that also processes tree nuts and soy.",
                a("SOY"), a("TREE_NUTS"));
        assertEquals(AnalysisReport.Status.MAY_CONTAIN, resultFor(report, a("SOY")).status());
        assertEquals(AnalysisReport.Status.MAY_CONTAIN, resultFor(report, a("TREE_NUTS")).status());
    }

    @Test
    public void containsStatementIsContains() {
        AnalysisReport report = run("Ingredients: flour, sugar, salt. Contains: wheat, milk.",
                a("WHEAT"), a("EGGS"));
        assertEquals(AnalysisReport.Status.CONTAINS, resultFor(report, a("WHEAT")).status());
        assertEquals(AnalysisReport.Status.NOT_DETECTED, resultFor(report, a("EGGS")).status());
    }

    @Test
    public void cocoaButterAndPeanutButterAreNotDairy() {
        AnalysisReport report = run("Ingredients: sugar, cocoa butter, shea butter, peanut butter, salt",
                a("MILK"));
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @Test
    public void plantMilksAreNotDairy() {
        AnalysisReport report = run("Ingredients: water, almond milk, coconut milk, oat milk, salt",
                a("MILK"));
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @Test
    public void wordsContainingAllergenNamesDoNotMatch() {
        AnalysisReport report = run("Ingredients: buckwheat flour, eggplant, butternut squash, water, salt",
                a("WHEAT"), a("EGGS"), a("MILK"));
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @Test
    public void glutenFreeClaimIsNotWheat() {
        AnalysisReport report = run("Ingredients: rice flour, corn starch, sugar. Gluten free.", a("WHEAT"));
        assertNotEquals(AnalysisReport.Status.CONTAINS, report.overall());
    }

    @Test
    public void nestedAllergenIsFound() {
        AnalysisReport report = run(
                "Ingredients: Chocolate (sugar, cocoa butter, soy lecithin), salt, water", a("SOY"));
        AllergenResult soy = resultFor(report, a("SOY"));
        assertEquals(AnalysisReport.Status.CONTAINS, soy.status());
        assertEquals("soy lecithin", soy.findings().get(0).evidence());
    }

    @Test
    public void plainLecithinIsOnlyPossibleSoy() {
        AnalysisReport report = run("Ingredients: sugar, cocoa butter, lecithin, vanilla", a("SOY"));
        AllergenResult soy = resultFor(report, a("SOY"));
        assertEquals(AnalysisReport.Status.POSSIBLE, soy.status());
        assertEquals(Finding.Reason.AMBIGUOUS_INGREDIENT, soy.findings().get(0).reason());
    }

    @Test
    public void sunflowerLecithinIsNotFlaggedAsSoy() {
        AnalysisReport report = run("Ingredients: sugar, cocoa, sunflower lecithin, vanilla", a("SOY"));
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @Test
    public void ocrTypoIsFlaggedAsPossibleNotMissed() {
        AnalysisReport report = run("Ingredients: sugar, peanu1 oil, salt, water", a("PEANUTS"));
        AllergenResult peanuts = resultFor(report, a("PEANUTS"));
        assertEquals(AnalysisReport.Status.POSSIBLE, peanuts.status());
        assertEquals(Finding.Reason.UNCERTAIN_READ, peanuts.findings().get(0).reason());
    }

    @Test
    public void wordSplitByOcrIsFlaggedAsPossible() {
        AnalysisReport report = run("Ingredients: sugar, pean ut oil, salt, water", a("PEANUTS"));
        assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
    }

    @Test
    public void batterIsNotButter() {
        AnalysisReport report = run("Ingredients: batter, better flour, water, salt", a("MILK"));
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @Test
    public void strongestEvidenceDecidesOverallStatusAndOrdering() {
        AnalysisReport report = run(
                "Ingredients: milk, sugar, salt. May contain peanuts.", a("PEANUTS"), a("MILK"), a("EGGS"));
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertEquals(a("MILK"), report.results().get(0).allergen());
        assertEquals(a("PEANUTS"), report.results().get(1).allergen());
        assertEquals(a("EGGS"), report.results().get(2).allergen());
    }

    // ---- safety: never "clear" when we can't tell ---------------------------------------------

    @Test
    public void emptyTextIsUnreadableNeverNotDetected() {
        for (String text : new String[]{"", "   ", null, "\n\n"}) {
            AnalysisReport report = run(text, a("PEANUTS"));
            assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
            assertEquals(AnalysisReport.Status.UNREADABLE, report.results().get(0).status());
        }
    }

    @Test
    public void garbageTextIsUnreadable() {
        AnalysisReport report = run("@@ ## 12 %%", a("MILK"));
        assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
    }

    @Test
    public void tooLittleTextIsUnreadable() {
        AnalysisReport report = run("salt", a("MILK"));
        assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
    }

    @Test
    public void noSelectedAllergensIsReportedExplicitly() {
        assertEquals(AnalysisReport.Status.NO_ALLERGENS_SELECTED,
                Analyzer.analyse("Ingredients: milk, sugar, salt", DICT,
                        Collections.<Allergen>emptyList()).overall());
        assertEquals(AnalysisReport.Status.NO_ALLERGENS_SELECTED,
                Analyzer.analyse("Ingredients: milk, sugar, salt", DICT, null).overall());
    }

    @Test
    public void croppedPanelWithoutHeadingStillMatchesButIsLowConfidence() {
        AnalysisReport report = run("sugar, wheat flour, salt, yeast, water", a("WHEAT"));
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertFalse(report.label().ingredientHeadingFound());
    }

    @Test
    public void sparseTextWithoutHeadingFlagsLowConfidence() {
        AnalysisReport report = run("sugar and some other long words here", a("WHEAT"));
        assertTrue(report.lowConfidence());
    }

    @Test
    public void allAllergensCanBeSelectedTogether() {
        AnalysisReport report = Analyzer.analyse(
                "Ingredients: wheat flour, milk, egg, peanut oil, almonds, soy, sesame, salmon, shrimp, mustard.",
                DICT, DICT.allergens());
        assertEquals(DICT.allergens().size(), report.results().size());
        for (AllergenResult r : report.results()) {
            assertEquals(r.allergen().toString(), AnalysisReport.Status.CONTAINS, r.status());
        }
    }

    @Test
    public void allergenLabelsRoundTrip() {
        for (Allergen allergen : DICT.allergens()) {
            assertEquals(allergen, DICT.byLabel(allergen.label()));
        }
        assertEquals(a("TREE_NUTS"), DICT.byLabel("tree nuts"));
    }
}
