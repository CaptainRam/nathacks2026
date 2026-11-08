package com.example.allergycheck.analysis;

import com.example.allergycheck.analysis.ai.AiDecision;
import com.example.allergycheck.analysis.ai.AiModel;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class AiAnalyzerTest {
    private final AllergenDictionary dictionary = TestDictionaries.shipped();

    private Allergen allergen(String id) {
        Allergen allergen = dictionary.byId(id);
        assertNotNull(allergen);
        return allergen;
    }

    private AnalysisReport matching(String text, String... ids) {
        List<Allergen> selected = new ArrayList<>();
        for (String id : ids) {
            selected.add(allergen(id));
        }
        return Analyzer.analyse(text, dictionary, selected);
    }

    @Test
    public void individualModelsCheckOneIngredientAndOneSelectedAllergenPerRequest() throws Exception {
        AnalysisReport base = matching("Ingredients: chocolate (sugar, cocoa butter), salt.", "MILK", "SOY");
        List<String> calls = new ArrayList<>();
        AnalysisReport report = AiAnalyzer.analyse(base, (ingredient, allergen) -> {
            assertFalse(ingredient.contains(","));
            assertFalse(ingredient.contains("("));
            calls.add(allergen.id() + ":" + ingredient);
            return AiDecision.of(AiDecision.Verdict.NO_EVIDENCE);
        });
        assertEquals(8, calls.size());
        assertTrue(calls.contains("MILK:chocolate"));
        assertTrue(calls.contains("SOY:cocoa butter"));
        assertTrue(report.aiAnalysis());
        assertFalse(report.aiIncomplete());
    }

    @Test
    public void inferenceNeverRemovesMatchingOrPrecautionaryEvidence() throws Exception {
        AnalysisReport base = matching("Ingredients: milk powder, sugar. May contain soy.", "MILK", "SOY");
        AnalysisReport report = AiAnalyzer.analyse(base, (ingredient, allergen) -> AiDecision.of(AiDecision.Verdict.NO_EVIDENCE));
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertEquals(Finding.Source.INGREDIENT, report.results().get(0).findings().get(0).source());
        assertEquals(AnalysisReport.Status.MAY_CONTAIN, report.results().get(1).status());
        assertEquals(Finding.Source.MAY_CONTAIN_STATEMENT,
                report.results().get(1).findings().get(0).source());
    }

    @Test
    public void riskUncertaintyAndFailedPairsKeepOriginalIngredientNames() throws Exception {
        AnalysisReport base = matching("Ingredients: mystery protein, unfamiliar flavour, salt.", "MILK");
        AnalysisReport report = AiAnalyzer.analyse(base, (ingredient, allergen) -> {
            if (ingredient.equals("mystery protein")) {
                return AiDecision.scored(0.87, 0.5);
            }
            if (ingredient.equals("unfamiliar flavour")) {
                return AiDecision.of(AiDecision.Verdict.UNCERTAIN);
            }
            throw new IOException("offline");
        });
        assertTrue(report.aiIncomplete());
        assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
        List<Finding> findings = report.results().get(0).findings();
        assertEquals(3, findings.size());
        assertEquals("mystery protein", findings.get(0).evidence());
        assertEquals(Finding.Reason.AI_RISK, findings.get(0).reason());
        assertEquals(0.87, findings.get(0).score(), 0.0001);
        assertEquals(Finding.Reason.AI_UNCERTAIN, findings.get(1).reason());
        assertEquals("salt", findings.get(2).evidence());
        assertEquals(Finding.Reason.AI_UNAVAILABLE, findings.get(2).reason());
    }

    @Test
    public void failedAllergenCannotBeHiddenBySuccessfulNegative() throws Exception {
        AnalysisReport report = AiAnalyzer.analyse(matching("Ingredients: sugar, water, salt.", "MILK", "SOY"),
                (ingredient, allergen) -> {
                    if (allergen.id().equals("MILK")) {
                        return AiDecision.of(AiDecision.Verdict.NO_EVIDENCE);
                    }
                    throw new IOException("offline");
                });
        assertTrue(report.aiIncomplete());
        assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
        assertTrue(report.results().stream().anyMatch(r -> r.allergen().id().equals("SOY")
                && r.status() == AnalysisReport.Status.UNREADABLE && !r.findings().isEmpty()));
    }

    @Test
    public void repeatedFailureStopsRequestsButEveryRemainingIngredientIsListed() throws Exception {
        int[] calls = {0};
        AnalysisReport report = AiAnalyzer.analyse(
                matching("Ingredients: alpha, beta, gamma, delta, epsilon.", "MILK"),
                (ingredient, allergen) -> { calls[0]++; throw new IOException(); });
        assertEquals(3, calls[0]);
        assertEquals(5, report.results().get(0).findings().size());
        assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
    }

    @Test
    public void partialSnapshotsKeepCompletedRisksAndListUncheckedIngredients() throws Exception {
        List<AnalysisReport> snapshots = new ArrayList<>();
        AiAnalyzer.analyse(matching("Ingredients: mystery powder, sugar.", "MILK"),
                (ingredient, allergen) -> AiDecision.of(AiDecision.Verdict.RISK), () -> false,
                (completed, total, report) -> snapshots.add(report));
        assertEquals(3, snapshots.size());
        assertTrue(snapshots.get(1).aiIncomplete());
        assertEquals(Finding.Reason.AI_RISK, snapshots.get(1).results().get(0).findings().get(0).reason());
        assertEquals(Finding.Reason.AI_UNAVAILABLE, snapshots.get(1).results().get(0).findings().get(1).reason());
        assertFalse(snapshots.get(2).aiIncomplete());
        // Reports already handed to a timeout callback cannot be mutated by later results.
        assertTrue(snapshots.get(1).aiIncomplete());
    }

    @Test
    public void shortAndHeadinglessInputsStillExposeFlaggedIngredient() throws Exception {
        AnalysisReport report = AiAnalyzer.analyse(matching("whey", "MILK"),
                (ingredient, allergen) -> AiDecision.of(AiDecision.Verdict.RISK));
        assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
        assertEquals("whey", report.results().get(0).findings().get(0).evidence());
        assertTrue(report.lowConfidence());
    }

    @Test
    public void emptyInputAndNoSelectionNeverBecomeClearResults() throws Exception {
        AiModel never = (ingredient, allergen) -> { throw new AssertionError("No input to evaluate"); };
        AnalysisReport empty = AiAnalyzer.analyse(matching("", "MILK"), never);
        assertEquals(AnalysisReport.Status.UNREADABLE, empty.overall());
        assertTrue(empty.aiIncomplete());
        assertTrue(empty.label().ingredients().isEmpty());
        AnalysisReport noSelection = AiAnalyzer.analyse(
                Analyzer.analyse("Ingredients: sugar, water.", dictionary, Collections.emptyList()), never);
        assertEquals(AnalysisReport.Status.NO_ALLERGENS_SELECTED, noSelection.overall());
    }

    @Test
    public void cloudLlmChecksCompleteIngredientListOnceForEachSelectedAllergen() throws Exception {
        List<String> calls = new ArrayList<>();
        List<String> expected = Arrays.asList("chocolate", "sugar", "mystery protein", "salt");
        AnalysisReport report = AiAnalyzer.analyse(matching(
                "Ingredients: chocolate (sugar, mystery protein), salt.", "MILK", "SOY"),
                new BatchModel() {
                    @Override
                    public List<AiDecision> evaluateBatch(List<String> ingredients, Allergen allergen) {
                        assertEquals(expected, ingredients);
                        calls.add(allergen.id());
                        List<AiDecision> decisions = new ArrayList<>();
                        for (String ingredient : ingredients) {
                            decisions.add(AiDecision.of(ingredient.equals("mystery protein")
                                    && allergen.id().equals("MILK")
                                    ? AiDecision.Verdict.RISK : AiDecision.Verdict.NO_EVIDENCE));
                        }
                        return decisions;
                    }
                });
        assertEquals(Arrays.asList("MILK", "SOY"), calls);
        assertFalse(report.aiIncomplete());
        assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
        assertTrue(report.results().stream().flatMap(result -> result.findings().stream())
                .anyMatch(finding -> finding.reason() == Finding.Reason.AI_RISK
                        && finding.evidence().equals("mystery protein")));
    }

    @Test
    public void missingCloudVerdictRetainsKnownRiskAndMarksOriginalIngredientUnchecked() throws Exception {
        AnalysisReport report = AiAnalyzer.analyse(matching(
                "Ingredients: mystery protein, sugar, salt.", "MILK"), new BatchModel() {
            @Override
            public List<AiDecision> evaluateBatch(List<String> ingredients, Allergen allergen) {
                return Arrays.asList(AiDecision.of(AiDecision.Verdict.RISK), null,
                        AiDecision.of(AiDecision.Verdict.NO_EVIDENCE));
            }
        });
        assertTrue(report.aiIncomplete());
        assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
        List<Finding> findings = report.results().get(0).findings();
        assertEquals(2, findings.size());
        assertEquals(Finding.Reason.AI_RISK, findings.get(0).reason());
        assertEquals("mystery protein", findings.get(0).evidence());
        assertEquals(Finding.Reason.AI_UNAVAILABLE, findings.get(1).reason());
        assertEquals("sugar", findings.get(1).evidence());
    }

    @Test
    public void failedCloudBatchDoesNotFallBackToIndividualRequestsOrEraseMatching() throws Exception {
        int[] calls = {0};
        AnalysisReport report = AiAnalyzer.analyse(matching(
                "Ingredients: milk powder, sugar, salt. May contain soy.", "MILK", "SOY"), new BatchModel() {
            @Override
            public List<AiDecision> evaluateBatch(List<String> ingredients, Allergen allergen) throws Exception {
                calls[0]++;
                throw new IOException("offline");
            }
        });
        assertEquals(2, calls[0]);
        assertTrue(report.aiIncomplete());
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        assertEquals(Finding.Source.INGREDIENT, report.results().get(0).findings().get(0).source());
        assertEquals(AnalysisReport.Status.MAY_CONTAIN, report.results().get(1).status());
    }

    @Test
    public void cloudProgressSnapshotRetainsCompletedAllergenAndUncheckedNextAllergen() throws Exception {
        List<AnalysisReport> snapshots = new ArrayList<>();
        List<Integer> completed = new ArrayList<>();
        AiAnalyzer.analyse(matching("Ingredients: mystery protein, salt.", "MILK", "SOY"),
                new BatchModel() {
                    @Override
                    public List<AiDecision> evaluateBatch(List<String> ingredients, Allergen allergen) {
                        return Arrays.asList(AiDecision.of(AiDecision.Verdict.RISK),
                                AiDecision.of(AiDecision.Verdict.NO_EVIDENCE));
                    }
                }, () -> false, (count, total, partial) -> {
                    assertEquals(4, total);
                    completed.add(count);
                    snapshots.add(partial);
                });
        assertEquals(Arrays.asList(0, 2, 4), completed);
        assertTrue(snapshots.get(1).aiIncomplete());
        assertTrue(snapshots.get(1).results().stream().anyMatch(r -> r.allergen().id().equals("MILK")
                && r.findings().get(0).reason() == Finding.Reason.AI_RISK));
        assertTrue(snapshots.get(1).results().stream().anyMatch(r -> r.allergen().id().equals("SOY")
                && r.findings().get(0).reason() == Finding.Reason.AI_UNAVAILABLE));
        assertFalse(snapshots.get(2).aiIncomplete());
    }

    private abstract static class BatchModel implements AiModel {
        @Override
        public boolean supportsBatch() {
            return true;
        }

        @Override
        public AiDecision evaluate(String ingredient, Allergen allergen) {
            throw new AssertionError("Cloud LLM must not send individual requests");
        }
    }
}
