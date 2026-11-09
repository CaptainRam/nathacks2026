package com.example.allergycheck.analysis;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Guards the 2026-11 ambiguous-term audit, in both directions.
 *
 * <p>The dictionary's ambiguous lists had accumulated two different kinds of term, and only one of
 * them works. A term earns its place if it is a <em>generic name for the allergen itself</em> —
 * "mixed nuts" might be peanuts, "seafood" might be fish — because there the ambiguity is in the
 * name and no label can resolve it. A term does <em>not</em> earn its place merely because the
 * allergen might be hiding inside it as an undeclared component: Canada's 2012 amendments (Food and
 * Drug Regulations B.01.010.1-.3) require priority allergens to be declared at component level,
 * including inside class-name ingredients such as "spices", "seasoning" and "flavour", so on a
 * compliant label there is nothing there to find. Roughly forty terms of the second kind were
 * removed, and the measured effect was that a plain soda stopped reporting four allergens and a
 * strawberry jam stopped reporting fish.
 *
 * <p>These are the cases most likely to be "helpfully" put back, so each one states what it is
 * protecting. The complementary direction is covered too: a term must not be dropped so far that a
 * label naming the allergen outright stops being caught.
 */
public class DictionaryNoiseTest {

    private static final AllergenDictionary DICT = TestDictionaries.shipped();

    // ---- removed: "could be hiding it" terms that a compliant label must declare anyway --------

    @Test
    public void naturalFlavourWarnsAboutNothing() {
        // It used to sit under MILK, EGGS, SOY and FISH at once, so any product listing it — which
        // is most of the grocery shelf — came back with four warnings. The term that started the
        // audit.
        assertQuiet("Ingredients: Carbonated water, sugar, citric acid, natural flavour, "
                + "sodium benzoate, caffeine.");
        assertQuiet("Ingredients: Strawberries, sugar, pectin, citric acid, natural flavour.");
    }

    @Test
    public void spicesAndSeasoningWarnAboutNothing() {
        // Both fired Sesame *and* Mustard, on nearly every savoury product. Mustard and sesame are
        // both priority allergens and must be named even inside a spice blend.
        // "canola oil", not "vegetable oil": the latter is a kept SOY term — soybean oil is the
        // commonest generic vegetable oil — so it would make this label legitimately noisy.
        assertQuiet("Ingredients: Potatoes, canola oil, sugar, salt, spices, onion powder.");
        assertQuiet("Ingredients: Rice flour, canola oil, salt, seasoning.");
        assertQuiet("Ingredients: Canola oil, water, vinegar, sugar, salt, spices, xanthan gum.");
    }

    @Test
    public void condimentsWarnAboutNothingUnlessTheyNameMustard() {
        // MUSTARD's whole ambiguous list went; see the notes on that entry. A condiment is no
        // longer suspected merely for being a condiment.
        assertQuiet("Ingredients: Water, vinegar, sugar, salt, tomato paste, onion powder.");
        assertStatus("Ingredients: Water, vinegar, mustard seed, salt, turmeric.",
                "MUSTARD", AnalysisReport.Status.CONTAINS);
        assertStatus("Ingredients: Canola oil, water, vinegar, dijon, salt.",
                "MUSTARD", AnalysisReport.Status.CONTAINS);
    }

    @Test
    public void gelatinDoesNotWarnAboutFish() {
        // Gelatin is overwhelmingly bovine or porcine, and fish gelatin must be declared as fish.
        // This put a fish warning on every bag of gummy sweets.
        assertQuiet("Ingredients: Sugar, glucose syrup, gelatin, citric acid, carnauba wax.");
        assertStatus("Ingredients: Sugar, fish gelatin, citric acid.",
                "FISH", AnalysisReport.Status.CONTAINS);
    }

    @Test
    public void soyLecithinNoLongerWarnsAboutEggs() {
        // The sharpest of the false positives: the label says *soy* lecithin and the report warned
        // about eggs, because bare "lecithin" was ambiguous for EGGS while SOY had the sense to
        // exclude the qualified forms. Egg lecithin is vanishingly rare and must be declared.
        assertStatus("Ingredients: Cocoa mass, sugar, cocoa butter, soy lecithin, vanilla.",
                "EGGS", AnalysisReport.Status.NOT_DETECTED);
        assertStatus("Ingredients: Cocoa mass, sugar, cocoa butter, soy lecithin, vanilla.",
                "SOY", AnalysisReport.Status.CONTAINS);
        assertStatus("Ingredients: Sugar, egg lecithin, salt.",
                "EGGS", AnalysisReport.Status.CONTAINS);
    }

    @Test
    public void chocolateAndCaramelDoNotWarnAboutMilk() {
        // Milk must be declared by name, so a compound ingredient cannot be concealing it.
        assertQuiet("Ingredients: Oats, sugar, canola oil, salt, chocolate.");
        assertQuiet("Ingredients: Sugar, glucose, caramel, salt.");
        assertStatus("Ingredients: Sugar, milk chocolate, salt.",
                "MILK", AnalysisReport.Status.CONTAINS);
    }

    @Test
    public void modifiedStarchDoesNotWarnAboutWheat() {
        // Wheat is both a priority allergen and a gluten source; a wheat-derived starch must say so.
        assertQuiet("Ingredients: Water, tomatoes, salt, sugar, modified food starch.");
        assertStatus("Ingredients: Water, salt, wheat starch.",
                "WHEAT", AnalysisReport.Status.CONTAINS);
    }

    // ---- kept: "is it, maybe" terms, where the ambiguity is in the name ------------------------

    @Test
    public void genericNamesForTheAllergenItselfStillWarn() {
        // These are the terms the audit deliberately kept. No label can resolve them, because the
        // ingredient's own name is the thing that is vague.
        assertStatus("Ingredients: Mixed nuts, salt.", "PEANUTS", AnalysisReport.Status.POSSIBLE);
        assertStatus("Ingredients: Mixed nuts, salt.", "TREE_NUTS", AnalysisReport.Status.POSSIBLE);
        assertStatus("Ingredients: Water, seafood, salt.", "FISH", AnalysisReport.Status.POSSIBLE);
        assertStatus("Ingredients: Sugar, hummus, salt.", "SESAME", AnalysisReport.Status.POSSIBLE);
        assertStatus("Ingredients: Water, vegetable oil, salt.", "SOY",
                AnalysisReport.Status.POSSIBLE);
    }

    // ---- "flour" promoted to a definite term --------------------------------------------------

    @Test
    public void unqualifiedFlourIsWheat() {
        // Food and Drug Regulations B.13.001: "flour", "white flour", "enriched flour" and "plain
        // flour" are standardised names for milled wheat, so this is wheat by law, not by guess.
        assertStatus("Ingredients: flour, water, salt, yeast.", "WHEAT",
                AnalysisReport.Status.CONTAINS);
        assertStatus("Ingredients: enriched flour, water, salt.", "WHEAT",
                AnalysisReport.Status.CONTAINS);
        assertStatus("Ingredients: white flour, sugar, salt.", "WHEAT",
                AnalysisReport.Status.CONTAINS);
    }

    @Test
    public void qualifiedNonWheatFloursAreNotWheat() {
        // The exclusion list became load-bearing when "flour" stopped being merely ambiguous: a gap
        // here is now a firm CONTAINS rather than a soft warning. "mustard flour" is in this list
        // because promoting the term produced exactly that false claim, which is what prompted
        // covering the nut, legume, seed, rye and barley flours too.
        String[] flours = {
            "rice", "corn", "almond", "coconut", "chickpea", "oat", "cassava", "potato", "tapioca",
            "buckwheat", "soy", "pea", "quinoa", "millet", "sorghum", "teff", "amaranth", "banana",
            "mustard", "rye", "barley", "lupin", "hazelnut", "peanut", "chestnut", "flax", "hemp",
            "sesame", "carob", "lentil", "bean",
        };
        for (String flour : flours) {
            assertStatus("Ingredients: " + flour + " flour, water, salt.", "WHEAT",
                    AnalysisReport.Status.NOT_DETECTED);
        }
    }

    @Test
    public void wheatFloursUnderAnotherNameAreStillWheat() {
        // The mirror of the test above: excluding non-wheat flours must not accidentally excuse the
        // wheat ones that do not have "wheat" in their name.
        assertStatus("Ingredients: semolina flour, water.", "WHEAT",
                AnalysisReport.Status.CONTAINS);
        assertStatus("Ingredients: graham flour, sugar.", "WHEAT",
                AnalysisReport.Status.CONTAINS);
        assertStatus("Ingredients: durum flour, water.", "WHEAT", AnalysisReport.Status.CONTAINS);
        assertStatus("Ingredients: spelt flour, water.", "WHEAT", AnalysisReport.Status.CONTAINS);
    }

    @Test
    public void flourStillRespectsAFreeFromClaim() {
        assertStatus("Ingredients: rice, water, salt. Gluten free.", "WHEAT",
                AnalysisReport.Status.NOT_DETECTED);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Asserts that no selected allergen reports anything at all for {@code label}. */
    private static void assertQuiet(String label) {
        List<Allergen> all = DICT.allergens();
        AnalysisReport report = Analyzer.analyse(label, DICT, all);
        StringBuilder noise = new StringBuilder();
        for (AllergenResult result : report.results()) {
            if (result.status() != AnalysisReport.Status.NOT_DETECTED) {
                String term = result.findings().isEmpty()
                        ? "?" : result.findings().get(0).matchedTerm();
                noise.append("\n  ").append(result.allergen().label())
                        .append('=').append(result.status())
                        .append(" via \"").append(term).append('"');
            }
        }
        assertEquals("expected no warnings for \"" + label + "\" but got:" + noise,
                "", noise.toString());
    }

    private static void assertStatus(String label, String allergenId,
            AnalysisReport.Status expected) {
        Allergen allergen = DICT.byId(allergenId);
        if (allergen == null) {
            throw new AssertionError("no allergen " + allergenId + " in the shipped dictionary");
        }
        AnalysisReport report = Analyzer.analyse(label, DICT, Arrays.asList(allergen));
        assertEquals(allergenId + " for \"" + label + "\"",
                expected, report.results().get(0).status());
    }
}
