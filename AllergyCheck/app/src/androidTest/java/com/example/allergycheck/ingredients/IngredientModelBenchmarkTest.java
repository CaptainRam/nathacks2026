package com.example.allergycheck.ingredients;

import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.analysis.Finding;
import com.example.allergycheck.analysis.IngredientItem;
import com.example.allergycheck.analysis.ParsedLabel;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reusable, opt-in measurement of the existing ingredient reader, not an accuracy assertion.
 *
 * <p>Push tools/model-benchmark/cases.json to the target app's external files/benchmark/cases.json,
 * then run this class with AndroidJUnitRunner. Results are written after every case to that same
 * benchmark directory as gemma-results.json and rules.json. Only the test APK needs installing;
 * the harness does not change the target APK, preferences, selected photo, or model files.
 *
 * <p>The public reader exposes parsed ingredients rather than the raw model response. Explicit
 * NONE therefore produces an empty successful list; exceptions and timeouts have separate
 * statuses and no ingredient list. A timeout stops further inference because the reader cannot
 * cancel an in-flight generation. The deterministic baseline is always saved first.
 * The first request allows 240 seconds for lazy initialization, and later requests allow 120.
 * Override the first limit with the instrumentation argument benchmarkColdTimeoutSeconds.
 *
 * <p>On an emulator these measurements are an extraction baseline, not phone performance data.
 */
@RunWith(AndroidJUnit4.class)
public class IngredientModelBenchmarkTest {
    private static final String TAG = "IngredientBenchmark";
    private static final long CASE_TIMEOUT_SECONDS = 120;
    private static final long COLD_TIMEOUT_SECONDS = 240;

    @Test
    public void benchmarkInstalledReaderAndRules() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "benchmark");
        assertTrue("Cannot create benchmark directory", directory.isDirectory() || directory.mkdirs());
        File input = new File(directory, "cases.json");
        assertTrue("Push benchmark/cases.json before running this test", input.isFile());
        JSONObject corpus = new JSONObject(Files.readString(input.toPath(), StandardCharsets.UTF_8));
        JSONArray cases = corpus.getJSONArray("cases");
        JSONObject environment = environment(context);

        JSONObject rules = document("deterministic IngredientExtractor + Analyzer", environment);
        JSONArray ruleCases = new JSONArray();
        rules.put("cases", ruleCases);
        for (int index = 0; index < cases.length(); index++) {
            JSONObject testCase = cases.getJSONObject(index);
            String text = testCase.getString("ocr_text");
            long started = SystemClock.elapsedRealtime();
            List<String> extracted = IngredientExtractor.extract(text);
            AnalysisReport report = Analyzer.analyse(extracted, text, EnumSet.allOf(Allergen.class));
            JSONObject result = identity(testCase);
            result.put("status", "ok");
            result.put("ingredients", new JSONArray(extracted));
            result.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
            result.put("analysis", analysis(report));
            result.put("home_shows_no_ingredients_message",
                    extracted.isEmpty() && report.label().statements().isEmpty());
            ruleCases.put(result);
        }
        rules.put("completed_at", Instant.now().toString());
        write(new File(directory, "rules.json"), rules);

        JSONObject gemma = document("installed Gemma via LlmIngredientReader", environment);
        gemma.put("timeout_per_case_seconds", CASE_TIMEOUT_SECONDS);
        long coldTimeout = Long.parseLong(InstrumentationRegistry.getArguments()
                .getString("benchmarkColdTimeoutSeconds", String.valueOf(COLD_TIMEOUT_SECONDS)));
        gemma.put("cold_timeout_seconds", coldTimeout);
        gemma.put("raw_response_available", false);
        gemma.put("timing_note", "First request includes lazy engine initialization; later requests reuse it. "
                + "Elapsed time includes prompt fitting, inference, parsing, and callback delivery. "
                + "Emulator timings do not estimate phone performance.");
        File model = LocalModelManager.installedModel(context);
        gemma.put("model_file", model == null ? JSONObject.NULL : model.getName());
        gemma.put("model_bytes", model == null ? JSONObject.NULL : model.length());
        JSONArray results = new JSONArray();
        gemma.put("cases", results);
        File output = new File(directory, "gemma-results.json");
        write(output, gemma);

        LlmIngredientReader reader = new LlmIngredientReader(context);
        boolean timedOut = false;
        try {
            for (int index = 0; index < cases.length(); index++) {
                JSONObject testCase = cases.getJSONObject(index);
                JSONObject result = identity(testCase);
                results.put(result);
                if (timedOut) {
                    result.put("status", "skipped_after_timeout");
                    result.put("error", "Earlier generation timed out and cannot be cancelled safely");
                    write(output, gemma);
                    continue;
                }
                result.put("status", "running");
                write(output, gemma);
                Log.i(TAG, "START " + testCase.getString("id"));
                CountDownLatch completed = new CountDownLatch(1);
                AtomicReference<List<String>> ingredients = new AtomicReference<>();
                AtomicReference<Exception> failure = new AtomicReference<>();
                long started = SystemClock.elapsedRealtime();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    try {
                        reader.read(testCase.optString("ocr_text"), new LlmIngredientReader.Callback() {
                            @Override
                            public void onSuccess(@NonNull List<String> items) {
                                ingredients.set(new ArrayList<>(items));
                                completed.countDown();
                            }

                            @Override
                            public void onFailure(@NonNull Exception error) {
                                failure.set(error);
                                completed.countDown();
                            }
                        });
                    } catch (Exception error) {
                        failure.set(error);
                        completed.countDown();
                    }
                });
                long timeoutSeconds = index == 0 ? coldTimeout : CASE_TIMEOUT_SECONDS;
                result.put("timeout_seconds", timeoutSeconds);
                boolean finished = completed.await(timeoutSeconds, TimeUnit.SECONDS);
                result.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
                if (!finished) {
                    timedOut = true;
                    result.put("status", "timeout");
                    result.put("error", "No reader callback within " + timeoutSeconds + " seconds");
                } else if (failure.get() != null) {
                    result.put("status", "error");
                    result.put("error", describe(failure.get()));
                } else {
                    result.put("status", "ok");
                    result.put("ingredients", new JSONArray(ingredients.get()));
                    result.put("returned_none", ingredients.get().isEmpty());
                }
                result.put("java_heap_used_bytes", Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory());
                write(output, gemma);
                Log.i(TAG, "END " + testCase.getString("id") + " "
                        + result.getString("status") + " " + result.getLong("elapsed_ms") + "ms");
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(reader::close);
            gemma.put("completed_at", Instant.now().toString());
            write(output, gemma);
        }
        Log.i(TAG, "RESULTS " + output.getAbsolutePath());
    }

    private static JSONObject document(String model, JSONObject environment) throws Exception {
        return new JSONObject().put("schema_version", 1).put("model", model)
                .put("platform", "android").put("started_at", Instant.now().toString())
                .put("environment", environment);
    }

    private static JSONObject identity(JSONObject testCase) throws Exception {
        return new JSONObject().put("id", testCase.getString("id"))
                .put("source_kind", testCase.optString("source_kind"))
                .put("source_group", testCase.optString("source_group"));
    }

    private static JSONObject environment(Context context) throws Exception {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        PackageInfo app = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        boolean emulator = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
                || Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone");
        return new JSONObject().put("is_emulator", emulator).put("model", Build.MODEL)
                .put("manufacturer", Build.MANUFACTURER).put("hardware", Build.HARDWARE)
                .put("fingerprint", Build.FINGERPRINT).put("android_release", Build.VERSION.RELEASE)
                .put("sdk_int", Build.VERSION.SDK_INT).put("abis", new JSONArray(Build.SUPPORTED_ABIS))
                .put("total_memory_bytes", memory.totalMem).put("available_memory_bytes", memory.availMem)
                .put("available_processors", Runtime.getRuntime().availableProcessors())
                .put("java_max_heap_bytes", Runtime.getRuntime().maxMemory())
                .put("target_package", context.getPackageName()).put("target_version_name", app.versionName)
                .put("target_version_code", app.getLongVersionCode());
    }

    private static JSONObject analysis(AnalysisReport report) throws Exception {
        JSONArray ingredients = new JSONArray();
        JSONArray items = new JSONArray();
        for (IngredientItem item : report.label().ingredients()) {
            if (item.depth() == 0) {
                ingredients.put(item.original());
            }
            items.put(new JSONObject().put("text", item.original()).put("depth", item.depth()));
        }
        JSONArray statements = new JSONArray();
        for (ParsedLabel.Statement statement : report.label().statements()) {
            statements.put(new JSONObject().put("kind", statement.kind().name()).put("text", statement.text()));
        }
        JSONArray allergens = new JSONArray();
        for (AllergenResult allergen : report.results()) {
            JSONArray findings = new JSONArray();
            for (Finding finding : allergen.findings()) {
                findings.put(new JSONObject().put("level", finding.level().name())
                        .put("source", finding.source().name()).put("reason", finding.reason().name())
                        .put("matched_term", finding.matchedTerm()).put("evidence", finding.evidence()));
            }
            allergens.put(new JSONObject().put("allergen", allergen.allergen().name())
                    .put("status", allergen.status().name()).put("findings", findings));
        }
        return new JSONObject().put("overall", report.overall().name())
                .put("low_confidence", report.lowConfidence())
                .put("ingredient_heading_found", report.label().ingredientHeadingFound())
                .put("top_level_ingredients", ingredients).put("parsed_items", items)
                .put("statements", statements).put("allergens", allergens);
    }

    private static String describe(Throwable error) {
        StringBuilder message = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (message.length() > 0) {
                message.append("; caused by ");
            }
            message.append(current.getClass().getName()).append(": ").append(current.getMessage());
        }
        return message.toString();
    }

    private static void write(File output, JSONObject value) throws Exception {
        Files.writeString(output.toPath(), value.toString(2), StandardCharsets.UTF_8);
    }
}
