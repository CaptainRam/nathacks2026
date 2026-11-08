package com.example.allergycheck;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.AiAnalyzer;
import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.analysis.Finding;
import com.example.allergycheck.analysis.ai.AiDecision;
import com.example.allergycheck.analysis.ai.AllergenPrompt;
import com.example.allergycheck.analysis.ai.LocalAiModel;
import com.example.allergycheck.ingredients.LocalModelManager;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Opt in with instrumentation argument {@code runLocalModelSmoke=true}, with Gemma installed.
 * Uses real inference, fixed synthetic inputs, and no network or preference changes.
 */
@RunWith(AndroidJUnit4.class)
public class LocalAiModelIntegrationTest {
    @Test
    public void realModelParsesVerdictsAndContinuesAfterThreeMalformedReplies() throws Exception {
        assumeTrue("Real inference is opt-in; pass -e runLocalModelSmoke true",
                Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                        .getString("runLocalModelSmoke", "false")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull("Download the real local model before running this test",
                LocalModelManager.installedModel(context));
        AllergenDictionary dictionary = AllergenDictionaryProvider.get(context);
        try (LocalAiModel model = new LocalAiModel(context)) {
            // These three negatives previously all failed because Gemma returned bare labels.
            check(model, dictionary, "salt", "MILK", AiDecision.Verdict.NO_EVIDENCE);
            check(model, dictionary, "sugar", "EGGS", AiDecision.Verdict.NO_EVIDENCE);
            check(model, dictionary, "rice", "PEANUTS", AiDecision.Verdict.NO_EVIDENCE);
            // Characterize a positive ingredient separately: parsing does not establish whether
            // this small model's prediction is correct. A negative must never erase label proof.
            Allergen milkAllergen = dictionary.byId("MILK");
            long caseinStarted = SystemClock.elapsedRealtime();
            AiDecision casein = model.evaluate("casein", milkAllergen);
            record("positive_ingredient_model_prediction", "casein", milkAllergen,
                    casein, caseinStarted);
            AnalysisReport knownPositive = AiAnalyzer.analyse(Analyzer.analyse(
                    "Ingredients: casein.", dictionary, Arrays.asList(milkAllergen)), model);
            assertEquals(AnalysisReport.Status.CONTAINS, knownPositive.overall());
            assertTrue(knownPositive.results().get(0).findings().stream()
                    .anyMatch(f -> f.evidence().equals("casein")
                            && f.level() == Finding.Level.CONTAINS));

            AnalysisReport matching = Analyzer.analyse("Ingredients: salt, sugar, rice.",
                    dictionary, Arrays.asList(dictionary.byId("MILK"), dictionary.byId("EGGS")));
            int[] attempts = {0};
            int[] completed = {0};
            int[] total = {0};
            AnalysisReport report = AiAnalyzer.analyse(matching, (ingredient, allergen) -> {
                if (++attempts[0] <= 3) {
                    // Inject malformed replies for the first allergen; the next uses real Gemma.
                    return AllergenPrompt.parseLocal("not a verdict");
                }
                long started = SystemClock.elapsedRealtime();
                AiDecision decision = model.evaluate(ingredient, allergen);
                record("continued_real_inference", ingredient, allergen, decision, started);
                return decision;
            }, () -> false, (done, count, partial) -> {
                completed[0] = done;
                total[0] = count;
            });

            assertEquals("Three malformed replies must not skip the next allergen", 6, attempts[0]);
            assertEquals(6, completed[0]);
            assertEquals(6, total[0]);
            assertTrue("Failed pairs must stay unchecked", report.aiIncomplete());
            AllergenResult milk = result(report, "MILK");
            assertEquals(AnalysisReport.Status.UNREADABLE, milk.status());
            assertEquals(3, milk.findings().size());
            for (Finding finding : milk.findings()) {
                assertEquals(Finding.Reason.AI_UNAVAILABLE, finding.reason());
            }
            AllergenResult eggs = result(report, "EGGS");
            assertEquals(AnalysisReport.Status.NOT_DETECTED, eggs.status());
            assertTrue(eggs.findings().isEmpty());
            recordSummary(attempts[0], completed[0]);
        }
    }

    private static void check(LocalAiModel model, AllergenDictionary dictionary,
                              String ingredient, String allergenId, AiDecision.Verdict expected)
            throws Exception {
        Allergen allergen = dictionary.byId(allergenId);
        long started = SystemClock.elapsedRealtime();
        AiDecision decision = model.evaluate(ingredient, allergen);
        record("real_inference", ingredient, allergen, decision, started);
        assertEquals(ingredient + " / " + allergen.label(), expected, decision.verdict());
    }

    private static AllergenResult result(AnalysisReport report, String allergenId) {
        return report.results().stream().filter(r -> r.allergen().id().equals(allergenId))
                .findFirst().orElseThrow(() -> new AssertionError("Missing allergen " + allergenId));
    }

    private static void record(String event, String ingredient, Allergen allergen,
                               AiDecision decision, long started) throws Exception {
        emit(new JSONObject().put("event", event).put("ingredient", ingredient)
                .put("allergen", allergen.label()).put("verdict", decision.verdict().name())
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started));
    }

    private static void recordSummary(int attempted, int completed) throws Exception {
        emit(new JSONObject().put("event", "continued_scan_complete")
                .put("attempted", attempted).put("completed", completed)
                .put("injected_format_failures", 3).put("later_real_checks", 3));
    }

    private static void emit(JSONObject event) {
        Bundle status = new Bundle();
        status.putString(Instrumentation.REPORT_KEY_STREAMRESULT,
                "LOCAL_MODEL_SMOKE " + event + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
