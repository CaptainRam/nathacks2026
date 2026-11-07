package com.example.allergycheck.ingredients;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class IngredientExtractorTest {

    private static final List<String> NONE = Collections.emptyList();

    private static List<String> ingredients(String text) {
        return IngredientExtractor.extract(text).ingredients();
    }

    private static List<String> additives(String text) {
        return IngredientExtractor.extract(text).additives();
    }

    private static void assertSplit(String text, List<String> ingredients, List<String> additives) {
        IngredientList list = IngredientExtractor.extract(text);
        assertEquals("ingredients", ingredients, list.ingredients());
        assertEquals("additives", additives, list.additives());
    }

    // ---- finding and splitting the list ---------------------------------------------------------

    @Test
    public void simpleList() {
        assertSplit("INGREDIENTS: Sugar, Cocoa Butter, Salt.",
                Arrays.asList("Sugar", "Cocoa Butter", "Salt"), NONE);
    }

    @Test
    public void textBeforeHeadingIsIgnored() {
        assertEquals(Arrays.asList("Rice", "Water"),
                ingredients("Crunchy Rice Snack 200g\nIngredients: Rice, Water"));
    }

    @Test
    public void nestedBracketsStayWithTheirParent() {
        assertEquals(Arrays.asList("Chocolate (sugar, cocoa butter, soy lecithin)", "wheat flour", "salt"),
                ingredients("Ingredients: Chocolate (sugar, cocoa butter, soy lecithin), wheat flour, salt"));
    }

    @Test
    public void squareBracketsAndSemicolons() {
        assertEquals(Arrays.asList("Seasoning [salt, garlic]", "oil", "vinegar"),
                ingredients("Ingredients: Seasoning [salt, garlic]; oil; vinegar"));
    }

    @Test
    public void listSpanningLinesWithHyphenatedWrap() {
        assertEquals(Arrays.asList("sugar", "peanut oil", "salt"),
                ingredients("Ingredients: sugar, pea-\nnut oil,\nsalt"));
    }

    @Test
    public void decimalPointDoesNotEndTheList() {
        assertEquals(Arrays.asList("water", "salt 0.5%", "sugar"),
                ingredients("Ingredients: water, salt 0.5%, sugar"));
    }

    @Test
    public void fullStopInsideBracketsDoesNotEndTheList() {
        assertSplit("Ingredients: water, Vitamins (vit. C, vit. E)",
                Arrays.asList("water"), Arrays.asList("Vitamins (vit. C, vit. E)"));
    }

    @Test
    public void stopsAtNutritionPanelWithoutFullStop() {
        assertEquals(Arrays.asList("rice", "salt"),
                ingredients("Ingredients: rice, salt\nNutrition Facts Calories 120 Fat 2g"));
    }

    @Test
    public void stopsAtFullStop() {
        assertEquals(Arrays.asList("rice", "salt"),
                ingredients("Ingredients: rice, salt. Keep in a cool, dry place."));
    }

    @Test
    public void allergenStatementsAreNotReturned() {
        assertEquals(Arrays.asList("flour", "sugar", "salt"),
                ingredients("Ingredients: flour, sugar, salt Contains: wheat, milk"));
        assertEquals(Arrays.asList("flour", "sugar"),
                ingredients("Ingredients: flour, sugar, may contain peanuts"));
        assertEquals(Arrays.asList("oats", "honey"),
                ingredients("Ingredients: oats, honey. Produced in a facility that processes tree nuts."));
    }

    @Test
    public void secondLanguageListIsNotAppended() {
        assertEquals(Arrays.asList("sugar", "milk"),
                ingredients("Ingredients: sugar, milk Ingrédients : sucre, lait"));
    }

    @Test
    public void frenchOnlyHeadingWorks() {
        assertEquals(Arrays.asList("sucre", "lait"), ingredients("INGRÉDIENTS : sucre, lait."));
    }

    @Test
    public void headingWithColonBeatsAnEarlierMention() {
        assertEquals(Arrays.asList("Wheat flour", "milk powder"),
                ingredients("Made with no artificial ingredients\nINGREDIENTS: Wheat flour, milk powder."));
    }

    @Test
    public void headingWithoutColonStillWorks() {
        assertEquals(Arrays.asList("Water", "Sugar"), ingredients("INGREDIENTS\nWater, Sugar"));
    }

    @Test
    public void noHeadingMeansNothingFound() {
        assertTrue(IngredientExtractor.extract("sugar, wheat flour, salt, sodium acetate").isEmpty());
        assertTrue(IngredientExtractor.extract("Nutrition Facts Calories 120").isEmpty());
    }

    @Test
    public void emptyOrGarbageInputMeansNothingFound() {
        for (String text : new String[]{null, "", "   \n ", "@@ ## 12 %%", "Ingredients:", "Ingredients: , ; 1 2"}) {
            assertTrue(String.valueOf(text), IngredientExtractor.extract(text).isEmpty());
        }
    }

    // ---- ingredients vs additives & chemicals -----------------------------------------------------

    @Test
    public void labelMarkerSplitsTheList() {
        assertSplit("Ingredients: corn, water, Contains 2% or less of: salt, sodium acetate, soy lecithin.",
                Arrays.asList("corn", "water"), Arrays.asList("salt", "sodium acetate", "soy lecithin"));
    }

    @Test
    public void labelMarkerVariants() {
        for (String marker : new String[]{
                "Contains less than 2% of", "Less than 2% of:", "2% or less of:", "and less than 1% of",
                "Contains 2% or less of each of the following:", "less than 0.5% of"}) {
            String text = "Ingredients: flour, sugar, " + marker + " yeast, calcium propionate";
            assertEquals(marker, Arrays.asList("flour", "sugar"), ingredients(text));
            assertEquals(marker, Arrays.asList("yeast", "calcium propionate"), additives(text));
        }
    }

    @Test
    public void markerInsideBracketsDoesNotSplitTheWholeList() {
        assertSplit("Ingredients: Spice blend (paprika, less than 2% of silicon dioxide), rice, water",
                Arrays.asList("Spice blend (paprika, less than 2% of silicon dioxide)", "rice", "water"), NONE);
    }

    @Test
    public void withoutMarkerChemicalsAreRecognisedByName() {
        assertSplit("Ingredients: flour, sugar, sodium acetate, citric acid, xanthan gum, salt",
                Arrays.asList("flour", "sugar", "salt"),
                Arrays.asList("sodium acetate", "citric acid", "xanthan gum"));
    }

    @Test
    public void eNumbersAreAdditives() {
        assertSplit("Ingredients: milk, sugar, E330, emulsifier E471, INS 322",
                Arrays.asList("milk", "sugar"), Arrays.asList("E330", "emulsifier E471", "INS 322"));
    }

    @Test
    public void functionalClassWithBracketsIsAnAdditive() {
        assertSplit("Ingredients: tomatoes, water, Preservative (sodium benzoate), Colour (caramel)",
                Arrays.asList("tomatoes", "water"),
                Arrays.asList("Preservative (sodium benzoate)", "Colour (caramel)"));
    }

    @Test
    public void bracketedParentIsJudgedByItsOwnName() {
        assertSplit("Ingredients: Enriched flour (wheat flour, niacin, reduced iron, thiamine mononitrate), water",
                Arrays.asList("Enriched flour (wheat flour, niacin, reduced iron, thiamine mononitrate)", "water"),
                NONE);
    }

    @Test
    public void commonChemicalFamiliesAreAdditives() {
        assertSplit("Ingredients: potatoes, vegetable oil, disodium pyrophosphate, mono- and diglycerides, "
                        + "potassium sorbate, titanium dioxide, monosodium glutamate, modified corn starch, BHT",
                Arrays.asList("potatoes", "vegetable oil"),
                Arrays.asList("disodium pyrophosphate", "mono- and diglycerides", "potassium sorbate",
                        "titanium dioxide", "monosodium glutamate", "modified corn starch", "BHT"));
    }

    @Test
    public void ordinaryFoodsAreNotMistakenForChemicals() {
        assertSplit("Ingredients: acidophilus milk, sesame seeds, ironbark honey, cornstarch, natural flavour",
                Arrays.asList("acidophilus milk", "sesame seeds", "ironbark honey", "cornstarch", "natural flavour"),
                NONE);
    }
}
