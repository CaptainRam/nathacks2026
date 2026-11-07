package com.example.allergycheck.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

public class AnalyzerTest {

    private static AnalysisReport run(String text, Allergen... allergens) {
        return Analyzer.analyse(text, Arrays.asList(allergens));
    }

    private static AllergenResult resultFor(AnalysisReport report, Allergen allergen) {
        for (AllergenResult r : report.results()) {
            if (r.allergen() == allergen) {
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
    public void bilingualLabelParsesBothLanguages() {
        AnalysisReport report = run("Ingredients: sugar, milk. Ingrédients: sucre, lait.", Allergen.MILK);
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
    }

    // ---- matching ----------------------------------------------------------------------------

    @Test
    public void ingredientMatchIsContains() {
        AnalysisReport report = run("Ingredients: sugar, whey powder, salt", Allergen.MILK);
        AllergenResult milk = resultFor(report, Allergen.MILK);
        assertEquals(AnalysisReport.Status.CONTAINS, milk.status());
        assertEquals("whey", milk.findings().get(0).matchedTerm());
        assertEquals(Finding.Source.INGREDIENT, milk.findings().get(0).source());
    }

    @Test
    public void mayContainStatementIsMayContainNotContains() {
        AnalysisReport report = run(
                "Ingredients: sugar, cocoa, salt. May contain peanuts.", Allergen.PEANUTS);
        assertEquals(AnalysisReport.Status.MAY_CONTAIN, report.overall());
        assertEquals(Finding.Source.MAY_CONTAIN_STATEMENT, report.results().get(0).findings().get(0).source());
    }

    @Test
    public void facilityStatementCountsAsMayContain() {
        AnalysisReport report = run(
                "Ingredients: oats, sugar, salt. Produced in a facility that also processes tree nuts and soy.",
                Allergen.SOY, Allergen.TREE_NUTS);
        assertEquals(AnalysisReport.Status.MAY_CONTAIN, resultFor(report, Allergen.SOY).status());
        assertEquals(AnalysisReport.Status.MAY_CONTAIN, resultFor(report, Allergen.TREE_NUTS).status());
    }

    @Test
    public void containsStatementIsContains() {
        AnalysisReport report = run("Ingredients: flour, sugar, salt. Contains: wheat, milk.",
                Allergen.WHEAT, Allergen.EGGS);
        assertEquals(AnalysisReport.Status.CONTAINS, resultFor(report, Allergen.WHEAT).status());
        assertEquals(AnalysisReport.Status.NOT_DETECTED, resultFor(report, Allergen.EGGS).status());
    }

    @Test
    public void cocoaButterAndPeanutButterAreNotDairy() {
        AnalysisReport report = run("Ingredients: sugar, cocoa butter, shea butter, peanut butter, salt",
                Allergen.MILK);
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @Test
    public void plantMilksAreNotDairy() {
        AnalysisReport report = run("Ingredients: water, almond milk, coconut milk, oat milk, salt",
                Allergen.MILK);
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @Test
    public void wordsContainingAllergenNamesDoNotMatch() {
        AnalysisReport report = run("Ingredients: buckwheat flour, eggplant, butternut squash, water, salt",
                Allergen.WHEAT, Allergen.EGGS, Allergen.MILK);
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @Test
    public void glutenFreeClaimIsNotWheat() {
        AnalysisReport report = run("Ingredients: rice flour, corn starch, sugar. Gluten free.", Allergen.WHEAT);
        assertNotEquals(AnalysisReport.Status.CONTAINS, report.overall());
    }

    @Test
    public void nestedAllergenIsFound() {
        AnalysisReport report = run(
                "Ingredients: Chocolate (sugar, cocoa butter, soy lecithin), salt, water", Allergen.SOY);
        AllergenResult soy = resultFor(report, Allergen.SOY);
        assertEquals(AnalysisReport.Status.CONTAINS, soy.status());
        assertEquals("soy lecithin", soy.findings().get(0).evidence());
    }

    @Test
    public void plainLecithinIsOnlyPossibleSoy() {
        AnalysisReport report = run("Ingredients: sugar, cocoa butter, lecithin, vanilla", Allergen.SOY);
        AllergenResult soy = resultFor(report, Allergen.SOY);
        assertEquals(AnalysisReport.Status.POSSIBLE, soy.status());
        assertEquals(Finding.Reason.AMBIGUOUS_INGREDIENT, soy.findings().get(0).reason());
    }

    @Test
    public void sunflowerLecithinIsNotFlaggedAsSoy() {
        AnalysisReport report = run("Ingredients: sugar, cocoa, sunflower lecithin, vanilla", Allergen.SOY);
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @Test
    public void ocrTypoIsFlaggedAsPossibleNotMissed() {
        AnalysisReport report = run("Ingredients: sugar, peanu1 oil, salt, water", Allergen.PEANUTS);
        AllergenResult peanuts = resultFor(report, Allergen.PEANUTS);
        assertEquals(AnalysisReport.Status.POSSIBLE, peanuts.status());
        assertEquals(Finding.Reason.UNCERTAIN_READ, peanuts.findings().get(0).reason());
    }

    @Test
    public void wordSplitByOcrIsFlaggedAsPossible() {
        AnalysisReport report = run("Ingredients: sugar, pean ut oil, salt, water", Allergen.PEANUTS);
        assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
    }

    @Test
    public void batterIsNotButter() {
        AnalysisReport report = run("Ingredients: batter, better flour, water, salt", Allergen.MILK);
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @Test
    public void strongestEvidenceDecidesOverallStatusAndOrdering() {
        AnalysisReport report = run(
                "Ingredients: milk, sugar, salt. May contain peanuts.", Allergen.PEANUTS, Allergen.MILK, Allergen.EGGS);
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertEquals(Allergen.MILK, report.results().get(0).allergen());
        assertEquals(Allergen.PEANUTS, report.results().get(1).allergen());
        assertEquals(Allergen.EGGS, report.results().get(2).allergen());
    }

    // ---- safety: never "clear" when we can't tell ---------------------------------------------

    @Test
    public void emptyTextIsUnreadableNeverNotDetected() {
        for (String text : new String[]{"", "   ", null, "\n\n"}) {
            AnalysisReport report = run(text, Allergen.PEANUTS);
            assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
            assertEquals(AnalysisReport.Status.UNREADABLE, report.results().get(0).status());
        }
    }

    @Test
    public void garbageTextIsUnreadable() {
        AnalysisReport report = run("@@ ## 12 %%", Allergen.MILK);
        assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
    }

    @Test
    public void tooLittleTextIsUnreadable() {
        AnalysisReport report = run("salt", Allergen.MILK);
        assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
    }

    @Test
    public void noSelectedAllergensIsReportedExplicitly() {
        assertEquals(AnalysisReport.Status.NO_ALLERGENS_SELECTED,
                Analyzer.analyse("Ingredients: milk, sugar, salt", Collections.<Allergen>emptyList()).overall());
        assertEquals(AnalysisReport.Status.NO_ALLERGENS_SELECTED,
                Analyzer.analyse("Ingredients: milk, sugar, salt", null).overall());
    }

    @Test
    public void croppedPanelWithoutHeadingStillMatchesButIsLowConfidence() {
        AnalysisReport report = run("sugar, wheat flour, salt, yeast, water", Allergen.WHEAT);
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertFalse(report.label().ingredientHeadingFound());
    }

    @Test
    public void sparseTextWithoutHeadingFlagsLowConfidence() {
        AnalysisReport report = run("sugar and some other long words here", Allergen.WHEAT);
        assertTrue(report.lowConfidence());
    }

    @Test
    public void allAllergensCanBeSelectedTogether() {
        AnalysisReport report = Analyzer.analyse(
                "Ingredients: wheat flour, milk, egg, peanut oil, almonds, soy, sesame, salmon, shrimp, mustard.",
                EnumSet.allOf(Allergen.class));
        assertEquals(Allergen.values().length, report.results().size());
        for (AllergenResult r : report.results()) {
            assertEquals(r.allergen().toString(), AnalysisReport.Status.CONTAINS, r.status());
        }
    }

    @Test
    public void allergenLabelsRoundTrip() {
        for (Allergen allergen : Allergen.values()) {
            assertEquals(allergen, Allergen.fromLabel(allergen.label()));
        }
        assertEquals(Allergen.TREE_NUTS, Allergen.fromLabel("tree nuts"));
    }
}
