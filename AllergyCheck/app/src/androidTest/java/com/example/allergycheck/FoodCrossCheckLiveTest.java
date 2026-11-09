package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.analysis.Finding;
import com.example.allergycheck.analysis.IngredientItem;
import com.example.allergycheck.crosscheck.FoodTypeCrossCheck;
import com.example.allergycheck.crosscheck.FoodTypeEstimate;
import com.example.allergycheck.history.ScanHistoryDatabase;
import com.example.allergycheck.history.ScanHistoryDetail;
import com.example.allergycheck.history.ScanHistoryImages;
import com.example.allergycheck.history.ScanHistoryStore;
import com.google.android.material.color.MaterialColors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Explicitly opt-in, billable live-provider test. Uses the app's actual controller/model and
 * prepared photographs; ordinary device suites skip it without making a network request.
 * Required arguments: runFoodCrossCheckLive=true, labelPhotoPath, frontPhotoPath. Optional:
 * modelId, allergenIds (comma-separated), expectedMissingAllergenId, fixtureSourceUrl.
 * expectedContainsAllergenIds (comma-separated) validates real ingredient evidence before upload.
 * requireMissingReminder=false allows a no-discrepancy control fixture (default: true).
 * preflightOnly=true checks the real OCR/report and writes baseline evidence without any upload.
 * labelTextPath explicitly selects a source-verified transcription instead of the OCR path;
 * that choice is recorded in the artifact and is never an automatic or silent fallback.
 * API credentials are read from saved app settings, never arguments, artifacts, or logging.
 */
@RunWith(AndroidJUnit4.class)
public class FoodCrossCheckLiveTest {
    @Test
    public void realCloudReminderDoesNotChangeIngredientReportOrHistory() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("A real cloud request must be explicitly enabled",
                "true".equals(args.getString("runFoodCrossCheckLive")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean preflightOnly = "true".equals(args.getString("preflightOnly"));
        boolean requireMissingReminder = !"false".equals(args.getString("requireMissingReminder"));
        if (!preflightOnly) {
            assertFalse("Save an OpenRouter API key before the live test",
                    AnalysisPreferences.openRouterApiKey(context).trim().isEmpty());
        }
        File labelPhoto = requiredFile(args, "labelPhotoPath");
        File frontPhoto = requiredFile(args, "frontPhotoPath");
        AllergenDictionary dictionary = AllergenDictionaryProvider.get(context);
        Set<String> selectedIds = new LinkedHashSet<>();
        String requested = args.getString("allergenIds", "");
        if (requested.trim().isEmpty()) {
            for (Allergen allergen : dictionary.allergens()) selectedIds.add(allergen.id());
        } else {
            for (String id : requested.split(",")) {
                assertNotNull("Unknown selected allergen ID", dictionary.byId(id.trim()));
                selectedIds.add(id.trim());
            }
        }
        SharedPreferences preferences = AllergenPreferences.preferences(context);
        Map<String, ?> previousPreferences = new HashMap<>(preferences.getAll());
        AnalysisPreferences.Mode previousMode = AnalysisPreferences.mode(context);
        String previousModel = AnalysisPreferences.foodTypeModel(context);
        ScanHistoryDatabase database = new ScanHistoryDatabase(context);
        File historyImages = new File(context.getFilesDir(), "history");
        ScanHistoryStore store = new ScanHistoryStore(database, historyImages);
        Set<Long> preexistingIds = new HashSet<>(store.liveIds());
        JSONObject evidence = new JSONObject();
        File artifacts = new File(context.getExternalFilesDir(null), "food-crosscheck-live");
        assertTrue(artifacts.isDirectory() || artifacts.mkdirs());
        try {
            preferences.edit().remove("selected_photo").commit();
            AllergenPreferences.saveSelectedAllergenIds(context, selectedIds);
            AllergenPreferences.saveShowNotDetected(context, true);
            AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
            String requestedModel = args.getString("modelId", "");
            if (!requestedModel.trim().isEmpty()) AnalysisPreferences.saveFoodTypeModel(context, requestedModel);
            if (!preflightOnly) {
                assertFalse("Save an image-capable model before the live test",
                        AnalysisPreferences.foodTypeModel(context).trim().isEmpty());
            }
            evidence.put("model", AnalysisPreferences.foodTypeModel(context));
            evidence.put("front_photo_sha256", sha256(Files.readAllBytes(frontPhoto.toPath())));
            evidence.put("label_photo_sha256", sha256(Files.readAllBytes(labelPhoto.toPath())));
            evidence.put("fixture_source_url", args.getString("fixtureSourceUrl", ""));
            evidence.put("preflight_only", preflightOnly);
            evidence.put("requires_missing_reminder", requireMissingReminder);
            evidence.put("cloud_executed", false);
            evidence.put("cloud_request_requested", false);
            evidence.put("uses_injected_model", false);
            JSONArray expectedContainsIds = new JSONArray();
            for (String id : args.getString("expectedContainsAllergenIds", "").split(",")) {
                if (!id.trim().isEmpty()) expectedContainsIds.put(id.trim());
            }
            evidence.put("expected_contains_allergen_ids", expectedContainsIds);
            String transcription = args.getString("labelTextPath", "");
            evidence.put("label_baseline_source", transcription.isEmpty()
                    ? "real ingredient photo through app OCR" : "explicit source-verified ingredient transcription");

            try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
                Uri labelUri = photoUri(context, labelPhoto);
                scan.onActivity(activity -> ScanTestSupport.invoke(activity, "loadPhoto",
                        new Class<?>[]{Uri.class}, labelUri));
                ScanTestSupport.awaitPhotoReady(scan);
                if (transcription.isEmpty()) {
                    ScanTestSupport.startCheck(scan);
                } else {
                    String realLabelText = Files.readString(requiredFile(args, "labelTextPath").toPath(),
                            StandardCharsets.UTF_8);
                    AnalysisReport transcribed = Analyzer.analyse(realLabelText, dictionary,
                            AllergenPreferences.selectedAllergenSet(context, dictionary));
                    scan.onActivity(activity -> {
                        ScanTestSupport.invoke(activity, "setAnalysisMode", new Class<?>[]{boolean.class}, true);
                        showTranscribedReport(activity, labelUri, transcribed);
                    });
                }
                FoodCrossCheckController controller = awaitLabelReport(scan);
                AnalysisReport original = (AnalysisReport) field(controller, "report");
                assertNotNull("A usable ingredient-label report is required", original);
                assertFalse("Ingredient OCR did not provide a usable ingredient list",
                        original.label().ingredients().isEmpty());
                byte[] originalBytes = serialized(original);
                AtomicReference<String> renderedBefore = new AtomicReference<>();
                scan.onActivity(activity -> {
                    renderedBefore.set(visibleText(activity.findViewById(R.id.analysis_result)));
                    saveContentScreenshot(activity, new File(artifacts, "before-front-photo.png"));
                });
                long historyId = awaitSavedRow(store, preexistingIds);
                HistorySnapshot historyBefore = historySnapshot(database, historyId);
                Set<Long> historyIdsBefore = new HashSet<>(store.liveIds());
                evidence.put("ingredient_report_before", reportJson(original));
                evidence.put("ingredient_report_before_sha256", sha256(originalBytes));
                evidence.put("history_report_before_sha256", sha256(historyBefore.report));
                evidence.put("history_row_count_before", historyIdsBefore.size());
                evidence.put("rendered_ingredient_result_before", renderedBefore.get());
                writeJson(new File(artifacts, "live-evidence.json"), evidence);

                for (int i = 0; i < expectedContainsIds.length(); i++) {
                    String id = expectedContainsIds.getString(i);
                    AllergenResult declared = original.results().stream()
                            .filter(result -> result.allergen().id().equals(id)).findFirst()
                            .orElseThrow(() -> new AssertionError("Expected ingredient allergen is not selected: " + id));
                    assertEquals("The real ingredient baseline did not identify the declared allergen: " + id,
                            AnalysisReport.Status.CONTAINS, declared.status());
                    assertFalse("The declared ingredient allergen has no supporting label evidence: " + id,
                            declared.findings().isEmpty());
                }

                if (preflightOnly) {
                    evidence.put("preflight_passed", true);
                    writeJson(new File(artifacts, "live-evidence.json"), evidence);
                    return;
                }

                evidence.put("cloud_request_requested", true);
                writeJson(new File(artifacts, "live-evidence.json"), evidence);
                scan.onActivity(activity -> controller.loadPhoto(photoUri(context, frontPhoto)));
                FoodTypeEstimate estimated = awaitCloudEstimate(scan, controller);
                evidence.put("cloud_executed", true);
                evidence.put("uses_real_cloud_model", true);
                FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(original, estimated);
                AtomicReference<String> reminder = new AtomicReference<>();
                AtomicReference<String> renderedAfter = new AtomicReference<>();
                scan.onActivity(activity -> {
                    reminder.set(((TextView) activity.findViewById(R.id.front_crosscheck_message))
                            .getText().toString());
                    renderedAfter.set(visibleText(activity.findViewById(R.id.analysis_result)));
                    saveContentScreenshot(activity, new File(artifacts, "after-cloud-crosscheck.png"));
                });
                AnalysisReport after = (AnalysisReport) field(controller, "report");
                HistorySnapshot historyAfter = historySnapshot(database, historyId);
                evidence.put("food_type", estimated.foodType());
                evidence.put("common_allergen_ids", allergenIds(estimated.commonAllergens()));
                evidence.put("reminder_allergen_ids", allergenIds(advice.missingAllergens()));
                evidence.put("reminder_text", reminder.get());
                evidence.put("ingredient_report_after", reportJson(after));
                evidence.put("ingredient_report_after_sha256", sha256(serialized(after)));
                evidence.put("history_report_after_sha256", sha256(historyAfter.report));
                evidence.put("history_row_count_after", store.liveIds().size());
                evidence.put("rendered_ingredient_result_after", renderedAfter.get());
                evidence.put("report_bytes_unchanged", Arrays.equals(originalBytes, serialized(after)));
                evidence.put("history_blob_unchanged", Arrays.equals(historyBefore.report, historyAfter.report));
                evidence.put("rendered_ingredient_result_unchanged", renderedBefore.get().equals(renderedAfter.get()));
                writeJson(new File(artifacts, "live-evidence.json"), evidence);

                if (requireMissingReminder) {
                    assertFalse("This fixture did not elicit any missing common-allergen reminders",
                            advice.missingAllergens().isEmpty());
                }
                if (advice.missingAllergens().isEmpty()) {
                    assertEquals("A no-discrepancy result must use the inconclusive no-reminders wording",
                            context.getString(R.string.crosscheck_no_missing, estimated.foodType()), reminder.get());
                } else {
                    assertTrue("The result must remain clearly advisory", reminder.get().contains("does not confirm"));
                    assertTrue(reminder.get().contains("not identified in the ingredient-label analysis"));
                }
                String expectedMissing = args.getString("expectedMissingAllergenId", "");
                if (!expectedMissing.isEmpty()) {
                    assertTrue("Expected missing allergen was not returned by the real model",
                            advice.missingAllergens().stream().anyMatch(a -> a.id().equals(expectedMissing)));
                }
                assertSame("The controller must retain the original label report object", original, after);
                assertArrayEquals("The cloud result changed the label report", originalBytes, serialized(after));
                assertEquals("The cloud result changed the rendered ingredient result",
                        renderedBefore.get(), renderedAfter.get());
                for (Allergen missing : advice.missingAllergens()) {
                    AllergenResult result = after.results().stream()
                            .filter(r -> r.allergen().equals(missing)).findFirst().orElseThrow(AssertionError::new);
                    assertEquals(AnalysisReport.Status.NOT_DETECTED, result.status());
                    assertTrue("A reminder must not create any ingredient finding", result.findings().isEmpty());
                }
                assertArrayEquals("The cloud result changed the saved report blob", historyBefore.report, historyAfter.report);
                assertEquals("The cloud result changed the stored verdict/summary/text",
                        historyBefore.columns, historyAfter.columns);
                assertEquals("A front-photo reminder must not create another ingredient-history scan",
                        historyIdsBefore, store.liveIds());
                evidence.put("passed", true);
                writeJson(new File(artifacts, "live-evidence.json"), evidence);
            }
        } finally {
            for (long id : store.liveIds()) {
                if (!preexistingIds.contains(id)) {
                    store.delete(id);
                    ScanHistoryImages.delete(historyImages, ScanHistoryImages.fileNameFor(id));
                }
            }
            database.close();
            AnalysisPreferences.saveMode(context, previousMode);
            AnalysisPreferences.saveFoodTypeModel(context, previousModel);
            restorePreferences(preferences, previousPreferences);
        }
    }

    private static FoodCrossCheckController awaitLabelReport(ScanTestSupport.Scan scan) {
        long deadline = SystemClock.uptimeMillis() + 30_000;
        do {
            AtomicReference<FoodCrossCheckController> result = new AtomicReference<>();
            scan.onActivity(activity -> {
                FoodCrossCheckController controller = (FoodCrossCheckController) field(activity, "foodCrossCheck");
                if (field(controller, "report") != null
                        && activity.findViewById(R.id.analysis_result).isShown()) result.set(controller);
            });
            if (result.get() != null) return result.get();
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No readable ingredient report appeared; inspect the real label/OCR fixture");
    }

    private static FoodTypeEstimate awaitCloudEstimate(ScanTestSupport.Scan scan, FoodCrossCheckController controller) {
        long deadline = SystemClock.uptimeMillis() + 120_000;
        String[] latest = {""};
        do {
            AtomicReference<FoodTypeEstimate> result = new AtomicReference<>();
            scan.onActivity(activity -> {
                result.set((FoodTypeEstimate) field(controller, "estimate"));
                latest[0] = ((TextView) activity.findViewById(R.id.front_crosscheck_message)).getText().toString();
            });
            if (result.get() != null) return result.get();
            if (latest[0].contains("could not") || latest[0].contains("Cloud settings")) {
                throw new AssertionError("Live cross-check did not complete: " + latest[0]);
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Live cloud request timed out: " + latest[0]);
    }

    private static long awaitSavedRow(ScanHistoryStore store, Set<Long> preexisting) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            for (long id : store.liveIds()) {
                ScanHistoryDetail detail = store.detail(id);
                if (!preexisting.contains(id) && detail != null && detail.report() != null
                        && detail.item().imageName() != null) return id;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The real ingredient scan was not saved with its label photo");
    }

    private static HistorySnapshot historySnapshot(ScanHistoryDatabase database, long id) {
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT report_blob, overall_status, summary_allergens, raw_text FROM scans WHERE _id=?",
                new String[]{Long.toString(id)})) {
            assertTrue(cursor.moveToFirst());
            return new HistorySnapshot(cursor.getBlob(0), cursor.getString(1) + "\u001f"
                    + cursor.getString(2) + "\u001f" + cursor.getString(3));
        }
    }

    private static final class HistorySnapshot {
        final byte[] report;
        final String columns;
        HistorySnapshot(byte[] report, String columns) { this.report = report; this.columns = columns; }
    }

    private static JSONObject reportJson(AnalysisReport report) throws Exception {
        JSONObject object = new JSONObject().put("overall", report.overall().name())
                .put("raw_text", report.label().rawText());
        JSONArray ingredients = new JSONArray();
        for (IngredientItem item : report.label().ingredients()) ingredients.put(item.original());
        JSONArray allergens = new JSONArray();
        for (AllergenResult result : report.results()) {
            JSONArray findings = new JSONArray();
            for (Finding finding : result.findings()) findings.put(new JSONObject()
                    .put("level", finding.level().name()).put("source", finding.source().name())
                    .put("reason", finding.reason().name()).put("evidence", finding.evidence()));
            allergens.put(new JSONObject().put("id", result.allergen().id())
                    .put("status", result.status().name()).put("findings", findings));
        }
        return object.put("ingredients", ingredients).put("allergens", allergens);
    }

    private static JSONArray allergenIds(Iterable<Allergen> allergens) {
        JSONArray ids = new JSONArray();
        for (Allergen allergen : allergens) ids.put(allergen.id());
        return ids;
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void showTranscribedReport(MainActivity activity, Uri label, AnalysisReport report) {
        try {
            Class<?> action = Class.forName("com.example.allergycheck.MainActivity$ResultAction");
            Method method = MainActivity.class.getDeclaredMethod("showAnalysisReport", Uri.class,
                    AnalysisReport.class, action);
            method.setAccessible(true);
            method.invoke(activity, label, report, null);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static File requiredFile(Bundle args, String key) {
        String path = args.getString(key, "");
        File file = new File(path);
        assertTrue("Missing prepared public test fixture: " + key, !path.isEmpty() && file.isFile());
        return file;
    }

    private static Uri photoUri(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
    }

    private static byte[] serialized(AnalysisReport report) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(report); }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static String visibleText(View view) {
        if (view.getVisibility() != View.VISIBLE) return "";
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) text.append(visibleText(group.getChildAt(i)));
        }
        return text.toString();
    }

    private static void saveContentScreenshot(MainActivity activity, File file) {
        View content = activity.findViewById(R.id.content_column);
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, content.getWidth()),
                Math.max(1, content.getHeight()), Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(file)) {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(MaterialColors.getColor(content, com.google.android.material.R.attr.colorSurface));
            content.draw(canvas);
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private static void writeJson(File file, JSONObject object) throws Exception {
        Files.writeString(file.toPath(), object.toString(2), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> snapshot) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : snapshot.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>) value));
        }
        assertTrue(editor.commit());
    }
}
