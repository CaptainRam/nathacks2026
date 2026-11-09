package com.example.allergycheck.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Report evidence and persistence cases from the analysis branch that main did not exercise. */
public class AnalyzerCoverageTest {

    private static final AllergenDictionary DICT = TestDictionaries.shipped();

    /** Z-11: keep each source and sort definite evidence ahead of warnings and ambiguity. */
    @Test
    public void repeatedAllergenEvidenceIsPreservedWithStrongestFindingsFirst() {
        AnalysisReport report = Analyzer.analyse(
                "Ingredients: nougat, milk, salt. Contains: milk. May contain milk.",
                DICT, Collections.singletonList(DICT.byId("MILK")));

        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        List<Finding> findings = report.results().get(0).findings();
        assertEquals(4, findings.size());
        assertEquals(Finding.Source.INGREDIENT, findings.get(0).source());
        assertEquals("milk", findings.get(0).evidence());
        assertEquals(Finding.Source.CONTAINS_STATEMENT, findings.get(1).source());
        assertEquals(Finding.Source.MAY_CONTAIN_STATEMENT, findings.get(2).source());
        assertEquals(Finding.Reason.AMBIGUOUS_INGREDIENT, findings.get(3).reason());
        assertEquals("nougat", findings.get(3).evidence());
        assertEquals(Arrays.asList(Finding.Level.CONTAINS, Finding.Level.CONTAINS,
                        Finding.Level.MAY_CONTAIN, Finding.Level.POSSIBLE),
                Arrays.asList(findings.get(0).level(), findings.get(1).level(),
                        findings.get(2).level(), findings.get(3).level()));
    }

    /** Z-12: report transport retains label structure and findings, without the dictionary. */
    @Test
    public void serializedReportRetainsAllDisplayContentWithoutDictionaryVocabulary() throws Exception {
        AnalysisReport original = Analyzer.analyse(
                "Ingredients: chocolate (sugar, milk fat), salt. May contain peanuts.",
                DICT, Arrays.asList(DICT.byId("MILK"), DICT.byId("PEANUTS"), DICT.byId("EGGS")));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        AnalysisReport restored;
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (AnalysisReport) input.readObject();
        }

        assertEquals(original.overall(), restored.overall());
        assertEquals(original.lowConfidence(), restored.lowConfidence());
        assertEquals(original.aiAnalysis(), restored.aiAnalysis());
        assertEquals(original.aiIncomplete(), restored.aiIncomplete());
        assertEquals(original.label().rawText(), restored.label().rawText());
        assertEquals(original.label().ingredientHeadingFound(), restored.label().ingredientHeadingFound());
        assertEquals(original.label().ingredients().size(), restored.label().ingredients().size());
        for (int i = 0; i < original.label().ingredients().size(); i++) {
            IngredientItem before = original.label().ingredients().get(i);
            IngredientItem after = restored.label().ingredients().get(i);
            assertEquals(before.original(), after.original());
            assertEquals(before.matchText(), after.matchText());
            assertEquals(before.depth(), after.depth());
        }
        assertEquals(original.label().statements().size(), restored.label().statements().size());
        for (int i = 0; i < original.label().statements().size(); i++) {
            assertEquals(original.label().statements().get(i).kind(),
                    restored.label().statements().get(i).kind());
            assertEquals(original.label().statements().get(i).text(),
                    restored.label().statements().get(i).text());
        }
        assertEquals(original.results().size(), restored.results().size());
        for (int i = 0; i < original.results().size(); i++) {
            AllergenResult before = original.results().get(i);
            AllergenResult after = restored.results().get(i);
            assertEquals(before.allergen(), after.allergen());
            assertEquals(before.allergen().label(), after.allergen().label());
            assertEquals(before.allergen().order(), after.allergen().order());
            assertEquals(before.status(), after.status());
            assertEquals(before.findings().size(), after.findings().size());
            for (int j = 0; j < before.findings().size(); j++) {
                Finding oldFinding = before.findings().get(j);
                Finding newFinding = after.findings().get(j);
                assertEquals(oldFinding.allergen(), newFinding.allergen());
                assertEquals(oldFinding.level(), newFinding.level());
                assertEquals(oldFinding.source(), newFinding.source());
                assertEquals(oldFinding.reason(), newFinding.reason());
                assertEquals(oldFinding.matchedTerm(), newFinding.matchedTerm());
                assertEquals(oldFinding.evidence(), newFinding.evidence());
                assertEquals(oldFinding.score(), newFinding.score());
            }
        }
        String stream = new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1);
        assertFalse(stream.contains(AllergenDictionary.class.getName()));
        assertFalse("unused dictionary vocabulary must not travel with the report",
                stream.contains("lactoglobulin"));
    }

    @Test
    public void truncatedExtractionIsLowConfidenceEvenWhenAHeadingWasFound() {
        String text = "Ingredients: sugar. whey powder, salt, cocoa.";
        // Supply a broken reader's result explicitly: the real extractor now keeps the whey.
        AnalysisReport report = Analyzer.analyse(Collections.singletonList("sugar"), text, DICT,
                Collections.singletonList(DICT.byId("MILK")));
        assertTrue(report.lowConfidence());
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertEquals("whey powder", report.results().get(0).findings().get(0).evidence());
    }

    @Test
    public void recoveringOmittedIngredientsDoesNotDuplicateStatementEvidence() {
        AnalysisReport report = Analyzer.analyse(Collections.singletonList("sugar"),
                "Ingredients: sugar, whey powder, salt. Contains: milk.", DICT,
                Collections.singletonList(DICT.byId("MILK")));

        assertTrue(report.lowConfidence());
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertEquals(2, report.results().get(0).findings().size());
        assertEquals(Finding.Source.CONTAINS_STATEMENT, report.results().get(0).findings().get(0).source());
        assertEquals(Finding.Source.INGREDIENT, report.results().get(0).findings().get(1).source());
    }

    @Test
    public void completeExtractionKeepsConfidenceAndDoesNotInventOtherPanelEvidence() {
        AnalysisReport report = Analyzer.analyse(Arrays.asList("Sugar", "SALT"),
                "Ingredients: sugar, salt. Nutrition Facts milk protein 2g.", DICT,
                Collections.singletonList(DICT.byId("MILK")));

        assertFalse(report.lowConfidence());
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
        assertTrue(report.results().get(0).findings().isEmpty());
    }

    @Test
    public void omittedNestedIngredientStillContributesItsOriginalEvidence() {
        AnalysisReport report = Analyzer.analyse(Arrays.asList("chocolate (sugar)", "salt"),
                "Ingredients: chocolate (sugar, milk fat), salt.", DICT,
                Collections.singletonList(DICT.byId("MILK")));

        assertTrue(report.lowConfidence());
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertEquals("milk fat", report.results().get(0).findings().get(0).evidence());
    }

    @Test
    public void unusableExternalListCannotHideTheReadableRawLabel() {
        AnalysisReport report = Analyzer.analyse(Collections.singletonList("@@"),
                "Ingredients: whey powder, sugar, salt.", DICT,
                Collections.singletonList(DICT.byId("MILK")));

        assertTrue(report.lowConfidence());
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertFalse(report.label().isEmpty());
        assertEquals("whey powder", report.results().get(0).findings().get(0).evidence());
    }
}
