package com.example.allergycheck.history;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The comma rules behind a history row's "Milk, Soy, and Wheat detected". */
public class AllergenNameListTest {

    private static final String AND = "and";

    @Test
    public void noNamesIsEmpty() {
        assertEquals("", AllergenNameList.format(Collections.emptyList(), AND));
        assertEquals("", AllergenNameList.format(null, AND));
    }

    @Test
    public void oneNameStandsAlone() {
        assertEquals("Milk", AllergenNameList.format(Collections.singletonList("Milk"), AND));
    }

    @Test
    public void twoNamesTakeNoComma() {
        assertEquals("Milk and Soy", AllergenNameList.format(Arrays.asList("Milk", "Soy"), AND));
    }

    @Test
    public void threeNamesTakeTheOxfordComma() {
        assertEquals("Milk, Soy, and Wheat",
                AllergenNameList.format(Arrays.asList("Milk", "Soy", "Wheat"), AND));
    }

    @Test
    public void fourNamesKeepCommasBetweenEveryPair() {
        assertEquals("Milk, Soy, Wheat, and Sesame",
                AllergenNameList.format(Arrays.asList("Milk", "Soy", "Wheat", "Sesame"), AND));
    }

    @Test
    public void blankAndNullEntriesAreSkippedWithoutLeavingPunctuation() {
        List<String> names = new ArrayList<>(Arrays.asList("Milk", "   ", null, "Soy"));
        assertEquals("Milk and Soy", AllergenNameList.format(names, AND));
        assertEquals("Milk", AllergenNameList.format(Arrays.asList("Milk", ""), AND));
        assertEquals("", AllergenNameList.format(Arrays.asList("", "  "), AND));
    }

    @Test
    public void reportOrderIsPreserved() {
        assertEquals("Wheat, Milk, and Soy",
                AllergenNameList.format(Arrays.asList("Wheat", "Milk", "Soy"), AND));
    }

    @Test
    public void aNameContainingACommaIsNotResplit() {
        assertEquals("Tree nuts, such as almonds and Milk",
                AllergenNameList.format(
                        Arrays.asList("Tree nuts, such as almonds", "Milk"), AND));
    }

    @Test
    public void namesAreTrimmed() {
        assertEquals("Milk and Soy", AllergenNameList.format(Arrays.asList(" Milk ", "Soy "), AND));
    }
}
