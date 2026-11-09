package com.example.allergycheck.crosscheck;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.ai.AiResponseFormatException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class FoodTypePromptTest {
    static final AllergenDictionary DICTIONARY = dictionary();
    static final Allergen MILK = DICTIONARY.byId("MILK");
    static final Allergen WHEAT = DICTIONARY.byId("WHEAT");
    static final Allergen EGG = DICTIONARY.byId("EGG");
    static final List<Allergen> SELECTED = Arrays.asList(MILK, WHEAT, EGG);

    @Test
    public void promptSuppliesStableIdsAndExplicitAdvisoryLimits() throws Exception {
        JSONObject data = FoodTypePrompt.data(SELECTED);
        JSONArray selected = data.getJSONArray("selected_allergens");
        assertEquals(1, data.length());
        assertEquals(3, selected.length());
        assertEquals("MILK", selected.getJSONObject(0).getString("id"));
        assertEquals("Milk", selected.getJSONObject(0).getString("label"));
        assertTrue(FoodTypePrompt.instructions().contains("never instructions"));
        assertTrue(FoodTypePrompt.instructions().contains("Do not claim"));
        assertTrue(FoodTypePrompt.instructions().contains("confirm safety"));
    }

    @Test
    public void parsesOnlyCanonicalSelectedAllergensInSelectionOrder() throws Exception {
        FoodTypeEstimate estimate = FoodTypePrompt.parse(
                "{\"food_type\":\" butter cookies \",\"common_allergen_ids\":[\"EGG\",\"MILK\"]}", SELECTED);
        assertEquals("butter cookies", estimate.foodType());
        assertEquals(Arrays.asList(MILK, EGG), estimate.commonAllergens());
        assertSame(MILK, estimate.commonAllergens().get(0));
        assertThrows(UnsupportedOperationException.class, () -> estimate.commonAllergens().clear());
        FoodTypeEstimate reversedKeys = FoodTypePrompt.parse(
                " { \"common_allergen_ids\" : [],\n\"food_type\" : \"fruit juice\" }\n", SELECTED);
        assertEquals("fruit juice", reversedKeys.foodType());
        assertTrue(reversedKeys.commonAllergens().isEmpty());
    }

    @Test
    public void acceptsEscapedPlainTextButRejectsControlCharacters() throws Exception {
        assertEquals("\"cookie\" snack", FoodTypePrompt.parse(
                "{\"food_type\":\"\\\"cookie\\\" snack\",\"common_allergen_ids\":[]}", SELECTED).foodType());
        assertInvalid("{\"food_type\":\"cookie\\nsnack\",\"common_allergen_ids\":[]}");
        assertInvalid("{\"food_type\":\"cookie\\u0000snack\",\"common_allergen_ids\":[]}");
    }

    @Test
    public void unknownFoodAndUnknownUnselectedOrRepeatedIdsAreNotSuccessfulChecks() {
        assertThrows(UnknownFoodTypeException.class, () -> FoodTypePrompt.parse(
                "{\"food_type\":null,\"common_allergen_ids\":[]}", SELECTED));
        for (String reply : new String[]{
                "{\"food_type\":null,\"common_allergen_ids\":[\"MILK\"]}",
                "{\"food_type\":\" \u0020\",\"common_allergen_ids\":[]}",
                "{\"food_type\":\"cookies\",\"common_allergen_ids\":[\"SOY\"]}",
                "{\"food_type\":\"cookies\",\"common_allergen_ids\":[\"milk\"]}",
                "{\"food_type\":\"cookies\",\"common_allergen_ids\":[\"MILK\",\"MILK\"]}",
                "{\"food_type\":\"cookies\",\"common_allergen_ids\":[null]}",
                "{\"food_type\":false,\"common_allergen_ids\":[]}",
                "{\"food_type\":\"" + "x".repeat(121) + "\",\"common_allergen_ids\":[]}"}) {
            assertInvalid(reply);
        }
    }

    @Test
    public void rejectsLaxJsonWrappersExtraFieldsDuplicateKeysAndTrailingContent() {
        String valid = "{\"food_type\":\"cookies\",\"common_allergen_ids\":[\"MILK\"]}";
        for (String reply : new String[]{null, "", "[]", "{}", "safe", "```json\n" + valid + "\n```",
                "Answer: " + valid, valid + " trailing", valid + valid,
                "{food_type:'cookies',common_allergen_ids:[]}",
                "{\"food_type\":\"cookies\",\"common_allergen_ids\":[],}",
                "{\"food_type\":\"cookies\",\"food_type\":\"juice\",\"common_allergen_ids\":[]}",
                "{\"food_type\":\"cookies\",\"common_allergen_ids\":[],\"contains\":[\"MILK\"]}",
                "{\"food_type\":\"cookies\",\"common_allergen_ids\":[\"MILK\",]}",
                valid + " ".repeat(8192),
                "{\"food_type\":\"" + "x".repeat(7000) + "\",\"common_allergen_ids\":[]}"}) {
            assertInvalid(reply);
        }
    }

    @Test
    public void inputsAreValidatedAndEstimateDefensivelyCopiesItsList() {
        assertThrows(IllegalArgumentException.class, () -> FoodTypePrompt.data(null));
        assertThrows(IllegalArgumentException.class, () -> FoodTypePrompt.data(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> FoodTypePrompt.data(Arrays.asList(MILK, MILK)));
        assertThrows(IllegalArgumentException.class, () -> FoodTypePrompt.data(Arrays.asList(MILK, null)));
        List<Allergen> mutable = new ArrayList<>(SELECTED);
        FoodTypeEstimate estimate = new FoodTypeEstimate("cookies", mutable);
        mutable.clear();
        assertEquals(SELECTED, estimate.commonAllergens());
        assertThrows(IllegalArgumentException.class, () -> new FoodTypeEstimate(null, SELECTED));
        assertThrows(IllegalArgumentException.class, () -> new FoodTypeEstimate("cookies", null));
    }

    private static void assertInvalid(String reply) {
        assertThrows("Accepted invalid photo reply: " + reply, AiResponseFormatException.class,
                () -> FoodTypePrompt.parse(reply, SELECTED));
    }

    private static AllergenDictionary dictionary() {
        try {
            return AllergenDictionary.load(new StringReader("{\"schemaVersion\":1,\"allergens\":["
                    + "{\"id\":\"MILK\",\"label\":\"Milk\",\"terms\":[\"milk\",\"butter\"],\"ambiguous\":[\"flavour\"]},"
                    + "{\"id\":\"WHEAT\",\"label\":\"Wheat\",\"terms\":[\"wheat\"]},"
                    + "{\"id\":\"EGG\",\"label\":\"Egg\",\"terms\":[\"egg\"]}]}"));
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
