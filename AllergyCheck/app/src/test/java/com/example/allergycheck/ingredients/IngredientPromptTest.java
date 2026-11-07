package com.example.allergycheck.ingredients;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

public class IngredientPromptTest {

    // ---- prompt -------------------------------------------------------------------------------

    @Test
    public void promptContainsTheLabelTextAndEndsReadyForTheReply() {
        String prompt = IngredientPrompt.build("RAREGRIDIENTS: sug4r, salt");
        assertTrue(prompt.contains("Label text: RAREGRIDIENTS: sug4r, salt"));
        assertTrue(prompt.endsWith("Reply:"));
        assertTrue(prompt.contains(IngredientPrompt.NONE));
    }

    @Test
    public void veryLongLabelTextIsCapped() {
        StringBuilder huge = new StringBuilder();
        while (huge.length() < IngredientPrompt.MAX_OCR_CHARS * 3) {
            huge.append("sugar, ");
        }
        String withoutText = IngredientPrompt.build("");
        String prompt = IngredientPrompt.build(huge.toString());
        assertEquals(withoutText.length() + IngredientPrompt.MAX_OCR_CHARS, prompt.length());
    }

    @Test
    public void nullTextIsTreatedAsEmpty() {
        assertEquals(IngredientPrompt.build(""), IngredientPrompt.build(null));
    }

    // ---- reply parsing ----------------------------------------------------------------------------

    @Test
    public void dashListIsParsedInOrder() {
        assertEquals(Arrays.asList("Oats", "sugar", "palm oil", "sodium bicarbonate"),
                IngredientPrompt.parse("- Oats\n- sugar\n- palm oil\n- sodium bicarbonate"));
    }

    @Test
    public void otherBulletAndNumberStylesAreAccepted() {
        assertEquals(Arrays.asList("water", "salt", "sugar", "yeast", "flour"),
                IngredientPrompt.parse("* water\n• salt\n1. sugar\n2) yeast\n-   flour  "));
    }

    @Test
    public void chatterAroundTheListIsIgnored() {
        assertEquals(Arrays.asList("rice", "water"),
                IngredientPrompt.parse("Here are the ingredients:\n\n- rice\n- water\n\nLet me know if you need more."));
    }

    @Test
    public void bracketsStayWithTheirIngredient() {
        assertEquals(Arrays.asList("Chocolate (sugar, cocoa butter, soy lecithin)", "salt"),
                IngredientPrompt.parse("- Chocolate (sugar, cocoa butter, soy lecithin)\n- salt"));
    }

    @Test
    public void markdownAndTrailingPunctuationAreStripped() {
        assertEquals(Arrays.asList("sugar", "salt", "citric acid"),
                IngredientPrompt.parse("- **sugar**\n- `salt`,\n- citric acid."));
    }

    @Test
    public void duplicatesAreDropped() {
        assertEquals(Arrays.asList("sugar", "salt"), IngredientPrompt.parse("- sugar\n- salt\n- sugar"));
    }

    @Test
    public void windowsLineEndingsWork() {
        assertEquals(Arrays.asList("sugar", "salt"), IngredientPrompt.parse("- sugar\r\n- salt\r\n"));
    }

    @Test
    public void noneMeansNoIngredients() {
        for (String reply : new String[]{"NONE", "None.", "**NONE**", "  none  ", "Answer: NONE", "- NONE"}) {
            assertTrue(reply, IngredientPrompt.parse(reply).isEmpty());
        }
    }

    @Test
    public void offScriptReplyIsAFailureNotAnEmptyList() {
        for (String reply : new String[]{"", "   ", null, "I cannot read this label.", "The ingredients are sugar and salt."}) {
            try {
                IngredientPrompt.parse(reply);
                fail("expected a failure for: " + reply);
            } catch (IllegalArgumentException expected) {
                // reported to the user as an error, never as "no ingredients found"
            }
        }
    }
}
