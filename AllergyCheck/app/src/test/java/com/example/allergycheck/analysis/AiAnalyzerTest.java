package com.example.allergycheck.analysis;

import com.example.allergycheck.analysis.ai.AiDecision;
import com.example.allergycheck.analysis.ai.AiModel;
import com.example.allergycheck.analysis.ai.AiResponseFormatException;
import com.example.allergycheck.analysis.ai.AiUnavailableException;

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
        // "chocolate" is already a dictionary match for milk, so only soy asks the model about it.
        assertEquals(7, calls.size());
        assertFalse(calls.contains("MILK:chocolate"));
        assertTrue(calls.contains("SOY:chocolate"));
        assertTrue(calls.contains("SOY:cocoa butter"));
        assertTrue(report.aiAnalysis());
        assertFalse(report.aiIncomplete());
    }

    @Test
    public void ingredientsAlreadyMatchedForAnAllergenAreNotSentToTheModel() throws Exception {
        AnalysisReport base = matching("Ingredients: milk powder, sugar.", "MILK", "SOY");
        List<String> calls = new ArrayList<>();
        AnalysisReport report = AiAnalyzer.analyse(base, (ingredient, allergen) -> {
            calls.add(allergen.id() + ":" + ingredient);
            return AiDecision.of(AiDecision.Verdict.RISK);
        });
        assertFalse(calls.contains("MILK:milk powder"));
        assertTrue(calls.contains("SOY:milk powder"));
        assertTrue(calls.contains("MILK:sugar"));
        assertFalse(report.aiIncomplete());
        AllergenResult milk = report.results().stream()
                .filter(r -> r.allergen().id().equals("MILK")).findFirst().get();
        assertEquals(1, milk.findings().stream()
                .filter(f -> f.evidence().equals("milk powder")).count());
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
    public void consecutiveFormatErrorsDoNotSkipLaterIngredientsOrAllergens() throws Exception {
        List<String> calls = new ArrayList<>();
        AnalysisReport report = AiAnalyzer.analyse(
                matching("Ingredients: alpha, beta, gamma, delta, epsilon.", "MILK", "SOY"),
                (ingredient, allergen) -> {
                    calls.add(allergen.id() + ":" + ingredient);
                    if (allergen.id().equals("MILK") && !ingredient.equals("epsilon")) {
                        throw new AiResponseFormatException("Invalid local verdict");
                    }
                    return AiDecision.of(allergen.id().equals("MILK")
                            ? AiDecision.Verdict.RISK : AiDecision.Verdict.NO_EVIDENCE);
                });

        assertEquals(10, calls.size());
        assertTrue(calls.contains("MILK:epsilon"));
        assertTrue(calls.contains("SOY:epsilon"));
        assertTrue(report.aiIncomplete());
        AllergenResult milk = report.results().stream()
                .filter(r -> r.allergen().id().equals("MILK")).findFirst().get();
        assertEquals(AnalysisReport.Status.POSSIBLE, milk.status());
        assertEquals(4, milk.findings().stream()
                .filter(f -> f.reason() == Finding.Reason.AI_UNAVAILABLE).count());
        assertTrue(milk.findings().stream().anyMatch(f -> f.evidence().equals("epsilon")
                && f.reason() == Finding.Reason.AI_RISK));
        AllergenResult soy = report.results().stream()
                .filter(r -> r.allergen().id().equals("SOY")).findFirst().get();
        assertEquals(AnalysisReport.Status.NOT_DETECTED, soy.status());
        assertTrue(soy.findings().isEmpty());
    }

    @Test
    public void allFormatErrorsRemainUncheckedAndEveryInputIsAttempted() throws Exception {
        int[] calls = {0};
        AnalysisReport report = AiAnalyzer.analyse(
                matching("Ingredients: alpha, beta, gamma, delta, epsilon.", "MILK"),
                (ingredient, allergen) -> {
                    calls[0]++;
                    throw new AiResponseFormatException("Invalid local verdict");
                });

        assertEquals(5, calls[0]);
        assertTrue(report.aiIncomplete());
        assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
        assertEquals(5, report.results().get(0).findings().size());
        assertTrue(report.results().get(0).findings().stream()
                .allMatch(f -> f.reason() == Finding.Reason.AI_UNAVAILABLE));
    }

    @Test
    public void formatRepliesBreakTheRunOfEngineFailures() throws Exception {
        int[] calls = {0};
        AnalysisReport report = AiAnalyzer.analyse(
                matching("Ingredients: alpha, beta, gamma, delta, epsilon, zeta.", "MILK"),
                (ingredient, allergen) -> {
                    int call = ++calls[0];
                    if (call == 3) {
                        throw new AiResponseFormatException("Invalid local verdict");
                    }
                    if (call < 6) throw new IOException("Engine unavailable");
                    return AiDecision.of(AiDecision.Verdict.RISK);
                });

        // Two runtime failures, one unusable reply, then two runtime failures still allow zeta.
        assertEquals(6, calls[0]);
        assertTrue(report.aiIncomplete());
        assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
        assertTrue(report.results().get(0).findings().stream()
                .anyMatch(f -> f.evidence().equals("zeta") && f.reason() == Finding.Reason.AI_RISK));
    }

    @Test
    public void unavailableModelStopsAfterTheFirstPair() throws Exception {
        int[] calls = {0};
        AnalysisReport report = AiAnalyzer.analyse(
                matching("Ingredients: alpha, beta, gamma, delta, epsilon.", "MILK"),
                (ingredient, allergen) -> {
                    calls[0]++;
                    throw new AiUnavailableException("The local model could not be loaded");
                });
        // A failed engine load repeats for every pair, so it must not be retried three times.
        assertEquals(1, calls[0]);
        assertTrue(report.aiIncomplete());
        assertEquals(5, report.results().get(0).findings().size());
    }

    @Test
    public void namelessBracketGroupIsNeitherCheckedNorReportedUnchecked() throws Exception {
        AnalysisReport base = matching("Ingredients: sugar, (soy lecithin), salt.", "MILK");
        // The group has no name of its own; its contents are a separate item.
        assertTrue(base.label().ingredients().stream()
                .anyMatch(item -> item.original().equals("(soy lecithin)")
                        && item.matchText().trim().isEmpty()));
        assertTrue(base.label().ingredients().stream()
                .anyMatch(item -> item.original().equals("soy lecithin")));
        List<String> calls = new ArrayList<>();
        AnalysisReport report = AiAnalyzer.analyse(base, (ingredient, allergen) -> {
            calls.add(ingredient);
            return AiDecision.of(AiDecision.Verdict.NO_EVIDENCE);
        });
        assertEquals(Arrays.asList("sugar", "soy lecithin", "salt"), calls);
        assertFalse(report.aiIncomplete());
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
        assertTrue(report.results().get(0).findings().isEmpty());
        assertFalse(AiAnalyzer.incompleteReport(base).results().get(0).findings().stream()
                .anyMatch(f -> f.evidence().isEmpty()));
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
        // The dictionary already flags "chocolate" for milk, so that batch omits it.
        List<String> all = Arrays.asList("chocolate", "sugar", "mystery protein", "salt");
        List<String> unmatchedForMilk = Arrays.asList("sugar", "mystery protein", "salt");
        AnalysisReport report = AiAnalyzer.analyse(matching(
                "Ingredients: chocolate (sugar, mystery protein), salt.", "MILK", "SOY"),
                new BatchModel() {
                    @Override
                    public List<AiDecision> evaluateBatch(List<String> ingredients, Allergen allergen) {
                        assertEquals(allergen.id().equals("MILK") ? unmatchedForMilk : all,
                                ingredients);
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
