package com.example.allergycheck.ingredients;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Missing X-series cases from the analysis branch's test plan, checked against current main. */
public class IngredientExtractorCoverageTest {

    @Test
    public void x13RealHyphenBeforeUppercaseContinuationIsPreserved() {
        assertEquals(Arrays.asList("SUGAR", "MONO- AND DIGLYCERIDES", "SALT"),
                IngredientExtractor.extract("INGREDIENTS: SUGAR, MONO-\nAND DIGLYCERIDES, SALT"));
    }

    @Test
    public void x33SemicolonReadInsteadOfHeadingColonStillFindsList() {
        assertEquals(Arrays.asList("oats", "whey powder", "salt"),
                IngredientExtractor.extract("Ingredients; oats, whey powder, salt"));
    }

    @Test
    public void x42FacilityStatementEndsListWithoutFullStop() {
        assertEquals(Arrays.asList("oats", "salt"), IngredientExtractor.extract(
                "Ingredients: oats, salt Processed in a facility that also processes peanuts."));
    }

    @Test
    public void x51UnclosedBracketDoesNotSwallowNutritionPanel() {
        assertEquals(Arrays.asList("sugar", "salt (iodized", "cocoa"), IngredientExtractor.extract(
                "Ingredients: sugar, salt (iodized, cocoa Nutrition Facts Calories 120 Fat 2g"));
    }

    @Test
    public void x51UnclosedBracketDoesNotSwallowAllergenStatement() {
        assertEquals(Arrays.asList("sugar", "salt (iodized", "cocoa"), IngredientExtractor.extract(
                "Ingredients: sugar, salt (iodized, cocoa May contain peanuts."));
    }

    @Test
    public void x52RepeatedExtractionHasNoStateFromPreviousLabels() {
        String label = "Ingredients: Chocolate (sugar, soy lecithin), salt. May contain peanuts.";
        List<String> first = IngredientExtractor.extract(label);
        assertEquals(Arrays.asList("Chocolate (sugar, soy lecithin)", "salt"), first);
        IngredientExtractor.extract("Ingredients: milk, wheat flour");
        assertEquals(first, IngredientExtractor.extract(label));
    }

    @Test
    public void x12UppercasePeanutWrapIsRejoined() {
        assertEquals(Arrays.asList("SUGAR", "PEANUT OIL"),
                IngredientExtractor.extract("INGREDIENTS: SUGAR, PEA-\nNUT OIL"));
    }

    @Test
    public void uncertainHyphenCompoundsAndFreeClaimsKeepTheirOriginalBoundary() {
        assertEquals(Arrays.asList("TREE- NUT", "MYSTERY- COMPOUND", "MILK- CHOCOLATE", "gluten- free"),
                IngredientExtractor.extract("Ingredients: TREE-\nNUT, MYSTERY-\nCOMPOUND, "
                        + "MILK-\nCHOCOLATE, gluten-\nfree"));
        // Main now ships "milkfat" as a known one-word spelling, unlike those compounds.
        assertEquals(Arrays.asList("MILKFAT"),
                IngredientExtractor.extract("Ingredients: MILK-\nFAT"));
    }

    @Test
    public void incidentalIngredientWordsAndLongerWordsDoNotBecomeHeadings() {
        for (String text : new String[] {"Made with simple ingredients you can trust.",
                "These INGREDIENTS are wholesome", "Made with simple ingredients you can trust, always.",
                "Simple lngredients: you can trust our brand.",
                "ingredientship: milk, salt", "microingredients: milk, salt"}) {
            assertTrue(text, IngredientExtractor.extract(text).isEmpty());
        }
    }

    @Test
    public void parenthesizedMarketingMentionDoesNotBeatTheActualHeading() {
        assertEquals(Arrays.asList("oats", "whey powder", "salt"), IngredientExtractor.extract(
                "Made with (simple ingredients: always fresh). INGREDIENTS oats, whey powder, salt"));
    }

    @Test
    public void incidentalSemicolonMentionDoesNotBeatTheRealColonHeading() {
        assertEquals(Arrays.asList("oats", "whey powder", "salt"), IngredientExtractor.extract(
                "Made with simple ingredients; we care. INGREDIENTS: oats, whey powder, salt."));
        assertEquals(Arrays.asList("oats", "whey powder", "salt"), IngredientExtractor.extract(
                "Made with simple ingredients; always fresh. INGREDIENTS: oats, whey powder, salt."));
    }

    @Test
    public void abbreviationAndOcrPeriodKeepFullListUntilActualStoragePanel() {
        assertEquals(Arrays.asList("sugar", "vit. C", "whey powder", "salt"),
                IngredientExtractor.extract("Ingredients: sugar, vit. C, whey powder. salt. "
                        + "Keep in a cool place. Serve with milk."));
    }

    @Test
    public void x20StrayPeriodDoesNotDiscardLaterAllergenIngredient() {
        assertTrue(IngredientExtractor.extract(
                "Ingredients: sugar, palm oil. whey powder, salt").contains("whey powder"));
    }

    @Test
    public void x21VitaminAbbreviationDoesNotDiscardLaterAllergenIngredient() {
        assertTrue(IngredientExtractor.extract(
                "Ingredients: sugar, vit. C, whey powder").contains("whey powder"));
    }

    @Test
    public void x30ActualHeadingWinsOverEarlierMarketingMention() {
        assertEquals(Arrays.asList("oats", "whey powder", "salt"), IngredientExtractor.extract(
                "Made with simple ingredients you can trust. INGREDIENTS oats, whey powder, salt"));
    }

    @Test
    public void x32PeriodReadInsteadOfHeadingColonStillFindsList() {
        assertEquals(Arrays.asList("oats", "whey powder"),
                IngredientExtractor.extract("INGREDIENTS. oats, whey powder"));
    }

    @Test
    public void x34LowercaseLAtStartOfHeadingIsTolerated() {
        assertEquals(Arrays.asList("oats", "salt"),
                IngredientExtractor.extract("lngredients: oats, salt"));
    }

    @Test
    public void x34LowercaseLInsideHeadingIsTolerated() {
        assertEquals(Arrays.asList("oats", "salt"),
                IngredientExtractor.extract("INGREDlENTS: oats, salt"));
    }

    @Test
    public void x41TracesWarningIsNotReturnedAsIngredient() {
        assertEquals(Arrays.asList("sugar", "cocoa butter"), IngredientExtractor.extract(
                "Ingredients: sugar, cocoa butter, traces of hazelnut"));
    }

    @Test
    public void x50ENumbersStayInTheIngredientList() {
        assertEquals(Arrays.asList("sugar", "E322", "E330", "salt"),
                IngredientExtractor.extract("Ingredients: sugar, E322, E330, salt"));
    }
}
