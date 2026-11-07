package com.example.allergycheck.ingredients;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class IngredientExtractorTest {

    private static List<String> extract(String text) {
        return IngredientExtractor.extract(text);
    }

    @Test
    public void simpleList() {
        assertEquals(Arrays.asList("Sugar", "Cocoa Butter", "Salt"),
                extract("INGREDIENTS: Sugar, Cocoa Butter, Salt."));
    }

    @Test
    public void textBeforeHeadingIsIgnored() {
        assertEquals(Arrays.asList("Rice", "Water"),
                extract("Crunchy Rice Snack 200g\nIngredients: Rice, Water"));
    }

    @Test
    public void nestedBracketsStayWithTheirParent() {
        assertEquals(Arrays.asList("Chocolate (sugar, cocoa butter, soy lecithin)", "wheat flour", "salt"),
                extract("Ingredients: Chocolate (sugar, cocoa butter, soy lecithin), wheat flour, salt"));
    }

    @Test
    public void squareBracketsAndSemicolons() {
        assertEquals(Arrays.asList("Seasoning [salt, garlic]", "oil", "vinegar"),
                extract("Ingredients: Seasoning [salt, garlic]; oil; vinegar"));
    }

    @Test
    public void listSpanningLinesWithHyphenatedWrap() {
        assertEquals(Arrays.asList("sugar", "peanut oil", "salt"),
                extract("Ingredients: sugar, pea-\nnut oil,\nsalt"));
    }

    @Test
    public void decimalPointDoesNotEndTheList() {
        assertEquals(Arrays.asList("water", "salt 0.5%", "sugar"),
                extract("Ingredients: water, salt 0.5%, sugar"));
    }

    @Test
    public void fullStopInsideBracketsDoesNotEndTheList() {
        assertEquals(Arrays.asList("Vitamins (vit. C, vit. E)", "water"),
                extract("Ingredients: Vitamins (vit. C, vit. E), water"));
    }

    @Test
    public void stopsAtNutritionPanelWithoutFullStop() {
        assertEquals(Arrays.asList("rice", "salt"),
                extract("Ingredients: rice, salt\nNutrition Facts Calories 120 Fat 2g"));
    }

    @Test
    public void stopsAtFullStop() {
        assertEquals(Arrays.asList("rice", "salt"),
                extract("Ingredients: rice, salt. Keep in a cool, dry place."));
    }

    @Test
    public void contains2PercentOrLessIsPartOfTheList() {
        assertEquals(Arrays.asList("corn", "Contains 2% or less of: salt", "soy lecithin"),
                extract("Ingredients: corn, Contains 2% or less of: salt, soy lecithin."));
    }

    @Test
    public void allergenStatementsAreNotReturned() {
        assertEquals(Arrays.asList("flour", "sugar", "salt"),
                extract("Ingredients: flour, sugar, salt Contains: wheat, milk"));
        assertEquals(Arrays.asList("flour", "sugar"),
                extract("Ingredients: flour, sugar, may contain peanuts"));
        assertEquals(Arrays.asList("oats", "honey"),
                extract("Ingredients: oats, honey. Produced in a facility that processes tree nuts."));
    }

    @Test
    public void secondLanguageListIsNotAppended() {
        assertEquals(Arrays.asList("sugar", "milk"),
                extract("Ingredients: sugar, milk Ingrédients : sucre, lait"));
    }

    @Test
    public void frenchOnlyHeadingWorks() {
        assertEquals(Arrays.asList("sucre", "lait"), extract("INGRÉDIENTS : sucre, lait."));
    }

    @Test
    public void headingWithColonBeatsAnEarlierMention() {
        assertEquals(Arrays.asList("Wheat flour", "milk powder"),
                extract("Made with no artificial ingredients\nINGREDIENTS: Wheat flour, milk powder."));
    }

    @Test
    public void headingWithoutColonStillWorks() {
        assertEquals(Arrays.asList("Water", "Sugar"), extract("INGREDIENTS\nWater, Sugar"));
    }

    @Test
    public void noHeadingMeansNothingFound() {
        assertTrue(extract("sugar, wheat flour, salt, yeast").isEmpty());
        assertTrue(extract("Nutrition Facts Calories 120").isEmpty());
    }

    @Test
    public void emptyOrGarbageInputMeansNothingFound() {
        assertTrue(extract(null).isEmpty());
        assertTrue(extract("").isEmpty());
        assertTrue(extract("   \n ").isEmpty());
        assertTrue(extract("@@ ## 12 %%").isEmpty());
        assertTrue(extract("Ingredients:").isEmpty());
        assertTrue(extract("Ingredients: , ; 1 2").isEmpty());
    }
}
