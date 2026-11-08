package com.example.allergycheck.crosscheck;

import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;

import static com.example.allergycheck.crosscheck.FoodTypePromptTest.*;
import static org.junit.Assert.*;

public class FoodTypeCrossCheckTest {
    @Test
    public void missingCategoryAllergensAreAdvisoriesAndCannotChangeTheLabelVerdict() {
        AnalysisReport report = Analyzer.analyse("Ingredients: sugar, cocoa, salt.", DICTIONARY, SELECTED);
        AllergenResult originalMilk = milkResult(report);
        FoodTypeCrossCheck check = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("chocolate cookies", SELECTED));
        assertEquals(SELECTED, check.missingAllergens());
        assertEquals(SELECTED, check.commonAllergens());
        assertEquals("chocolate cookies", check.foodType());
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
        assertSame(originalMilk, milkResult(report));
        assertTrue(originalMilk.findings().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> check.missingAllergens().clear());
        assertThrows(UnsupportedOperationException.class, () -> check.commonAllergens().clear());
    }

    @Test
    public void containsPrecautionaryAndPossibleLabelFindingsAreAlreadyIdentified() {
        for (String text : new String[]{"Ingredients: sugar, milk, salt.",
                "Ingredients: sugar, cocoa, salt. May contain milk.",
                "Ingredients: sugar, flavour, salt."}) {
            AnalysisReport report = Analyzer.analyse(text, DICTIONARY, SELECTED);
            assertNotEquals(AnalysisReport.Status.NOT_DETECTED, milkResult(report).status());
            FoodTypeCrossCheck check = FoodTypeCrossCheck.compare(report,
                    new FoodTypeEstimate("cookies", SELECTED));
            assertFalse(check.missingAllergens().contains(MILK));
            assertTrue(check.missingAllergens().contains(WHEAT));
            assertTrue(check.missingAllergens().contains(EGG));
        }
    }

    @Test
    public void noAdditionalReminderDoesNotAlterAnExistingContainsVerdict() {
        AnalysisReport report = Analyzer.analyse("Ingredients: milk, sugar, salt.", DICTIONARY, SELECTED);
        FoodTypeCrossCheck check = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("drink", Collections.emptyList()));
        assertTrue(check.missingAllergens().isEmpty());
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
    }

    @Test
    public void unreadableOrUnselectedResultsCannotProduceAbsenceReminders() {
        FoodTypeEstimate estimate = new FoodTypeEstimate("cookies", SELECTED);
        assertThrows(IllegalArgumentException.class, () -> FoodTypeCrossCheck.compare(
                Analyzer.analyse("", DICTIONARY, SELECTED), estimate));
        assertThrows(IllegalArgumentException.class, () -> FoodTypeCrossCheck.compare(
                Analyzer.analyse("Ingredients: sugar, cocoa, salt.", DICTIONARY, Collections.emptyList()), estimate));
        assertThrows(IllegalArgumentException.class, () -> FoodTypeCrossCheck.compare(
                Analyzer.analyse("Ingredients: sugar, cocoa, salt.", DICTIONARY, Collections.singletonList(MILK)), estimate));
    }

    @Test
    public void estimateAndAdvisorySurviveSerializableStateRestoration() throws Exception {
        FoodTypeEstimate estimate = roundTrip(new FoodTypeEstimate("cookies", SELECTED));
        assertEquals(SELECTED, estimate.commonAllergens());
        FoodTypeCrossCheck check = roundTrip(FoodTypeCrossCheck.compare(
                Analyzer.analyse("Ingredients: milk, sugar, salt.", DICTIONARY, SELECTED), estimate));
        assertEquals("cookies", check.foodType());
        assertEquals(SELECTED, check.commonAllergens());
        assertFalse(check.missingAllergens().contains(MILK));
        assertTrue(check.missingAllergens().contains(WHEAT));
    }

    private static AllergenResult milkResult(AnalysisReport report) {
        for (AllergenResult result : report.results()) if (result.allergen().equals(MILK)) return result;
        throw new AssertionError("Missing selected milk result");
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(value); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
