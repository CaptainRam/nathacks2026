package com.example.allergycheck;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inspector.WindowInspector;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Finding;
import com.example.allergycheck.crosscheck.FoodTypeCrossCheck;
import com.example.allergycheck.crosscheck.FoodTypeEstimate;
import com.example.allergycheck.history.ScanHistoryDatabase;
import com.example.allergycheck.history.ScanHistoryDetail;
import com.example.allergycheck.history.ScanHistoryImages;
import com.example.allergycheck.history.ScanHistoryStore;
import com.google.android.material.card.MaterialCardView;
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
import java.util.function.Predicate;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Opt-in real-photo regression. Required arguments: runRealPhotoUi=true, labelPhotoPath,
 * frontPhotoPath, replacementFrontPhotoPath. runLiveCloud=true additionally uses the saved
 * OpenRouter key and modelId (or the saved vision model); normal suites never send requests.
 * All photo changes go through the native popup and real activity-result callback. No model or
 * ingredient report is injected. Credentials are absent from all artifacts and test arguments.
 */
@RunWith(AndroidJUnit4.class)
public class RealPhotoUiTest {
    private FoodTypeCrossCheck lastCompletedAdvice;
    private Context context;
    private File artifacts;

    @Test
    public void realPhotosStayPairedAndFrontEditsPreserveIngredientReport() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Real photographs must be explicitly prepared", "true".equals(args.getString("runRealPhotoUi")));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File label = requiredFile(args, "labelPhotoPath");
        File front = requiredFile(args, "frontPhotoPath");
        File replacement = requiredFile(args, "replacementFrontPhotoPath");
        boolean live = "true".equals(args.getString("runLiveCloud"));
        String model = args.getString("modelId", AnalysisPreferences.foodTypeModel(context)).trim();
        if (live) {
            assertFalse("Save the cloud API key before enabling the live test", AnalysisPreferences.openRouterApiKey(context).trim().isEmpty());
            assertFalse("Choose a vision model before enabling the live test", model.isEmpty());
        }
        Map<String, Map<String, ?>> saved = new HashMap<>();
        for (String name : new String[]{"analysis_preferences", "analysis_credentials", "allergy_preferences"}) {
            saved.put(name, new HashMap<>(context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll()));
        }
        ScanHistoryDatabase database = new ScanHistoryDatabase(context);
        File historyImages = new File(context.getFilesDir(), "history");
        ScanHistoryStore store = new ScanHistoryStore(database, historyImages);
        Set<Long> oldHistory = new HashSet<>(store.liveIds());
        artifacts = new File(context.getExternalFilesDir(null), "real-photo-ui");
        assertTrue(artifacts.isDirectory() || artifacts.mkdirs());
        JSONObject evidence = new JSONObject().put("live_cloud_enabled", live)
                .put("model", live ? model : JSONObject.NULL).put("uses_injected_model", false)
                .put("label_source", "real photograph through MainActivity OCR")
                .put("cloud_deferred_until_baseline", true)
                .put("label_photo_sha256", sha256(Files.readAllBytes(label.toPath())))
                .put("front_photo_sha256", sha256(Files.readAllBytes(front.toPath())))
                .put("replacement_front_photo_sha256", sha256(Files.readAllBytes(replacement.toPath())))
                .put("fixture_source_url", args.getString("fixtureSourceUrl", ""));
        assertNotEquals("Replacement must be a different real photograph", evidence.getString("front_photo_sha256"),
                evidence.getString("replacement_front_photo_sha256"));
        try {
            AllergenPreferences.preferences(context).edit().remove("selected_photo").commit();
            AllergenPreferences.saveSelectedAllergenIds(context,
                    new LinkedHashSet<>(Arrays.asList("MILK", "EGGS", "SOY", "WHEAT", "TREE_NUTS")));
            AllergenPreferences.saveShowNotDetected(context, true);
            AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
            // Capture the OCR report before even a fast real provider can answer.
            AnalysisPreferences.saveFoodTypeModel(context, "");
            if (!live) AnalysisPreferences.saveOpenRouterApiKey(context, "");
            try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
                scan.onActivity(activity -> ScanTestSupport.invoke(activity, "loadPhoto", new Class<?>[]{Uri.class}, uri(label)));
                ScanTestSupport.awaitPhotoReady(scan);
                selectFrontFromGallery(scan, uri(front), false);
                await(scan, activity -> controller(activity).currentBitmap() != null && !controller(activity).isPhotoLoading());
                AtomicReference<JSONObject> homeGeometry = new AtomicReference<>();
                scan.onActivity(activity -> {
                    MaterialCardView left = activity.findViewById(R.id.photo_card);
                    MaterialCardView right = activity.findViewById(R.id.front_photo_card);
                    assertPair(left, right);
                    assertSame(left.getParent(), right.getParent());
                    homeGeometry.set(json("ingredient", geometry(left), "front", geometry(right)));
                    assertEquals(ImageView.ScaleType.FIT_CENTER, ((ImageView) activity.findViewById(R.id.front_photo_preview)).getScaleType());
                    saveContent(activity, "home-real-pair.png");
                });
                saveViewport("home-real-pair-viewport.png");
                evidence.put("home_pair", homeGeometry.get());
                writeEvidence(evidence);

                ScanTestSupport.startCheck(scan);
                await(scan, activity -> field(controller(activity), "report") != null && activity.findViewById(R.id.analysis_result).isShown());
                AtomicReference<Baseline> captured = new AtomicReference<>();
                scan.onActivity(activity -> captured.set(new Baseline(activity)));
                Baseline baseline = captured.get();
                assertFalse("The real photo must produce ingredients, not just an empty read", baseline.report.label().ingredients().isEmpty());
                for (String id : Arrays.asList("MILK", "EGGS", "SOY")) {
                    AllergenResult found = result(baseline.report, id);
                    assertEquals("Declared allergen was not identified from the real label: " + id,
                            AnalysisReport.Status.CONTAINS, found.status());
                    assertFalse(found.findings().isEmpty());
                }
                assertEquals(AnalysisReport.Status.NOT_DETECTED, result(baseline.report, "WHEAT").status());
                assertTrue(result(baseline.report, "WHEAT").findings().isEmpty());
                long historyId = awaitHistory(store, oldHistory);
                HistorySnapshot history = history(database, historyId);
                Set<Long> historyIds = new HashSet<>(store.liveIds());
                evidence.put("ingredient_report_before", reportJson(baseline.report))
                        .put("baseline_report_sha256", sha256(baseline.bytes))
                        .put("baseline_history_report_sha256", sha256(history.report))
                        .put("baseline_history_row_count", historyIds.size());
                writeEvidence(evidence);
                if (live) {
                    AnalysisPreferences.saveFoodTypeModel(context, model);
                    scan.onActivity(activity -> controller(activity).refreshConfiguration());
                    awaitCloud(scan);
                    AtomicReference<FoodTypeCrossCheck> firstAdvice = new AtomicReference<>();
                    scan.onActivity(activity -> firstAdvice.set(FoodTypeCrossCheck.compare(baseline.report,
                            (FoodTypeEstimate) field(controller(activity), "estimate"))));
                    assertTrue("The first real photo should exercise the missing-wheat reminder",
                            firstAdvice.get().missingAllergens().stream().anyMatch(a -> "WHEAT".equals(a.id())));
                }
                JSONObject initial = stage(scan, baseline, database, store, historyId, history, historyIds, true);
                evidence.put("initial_result", initial);
                scan.onActivity(activity -> saveContent(activity, "result-real-pair.png"));
                saveViewport("result-real-pair-viewport.png");
                writeEvidence(evidence);

                AtomicReference<Bitmap> previousBitmap = new AtomicReference<>();
                AtomicReference<Uri> previousPhoto = new AtomicReference<>();
                scan.onActivity(activity -> {
                    previousBitmap.set(controller(activity).currentBitmap());
                    previousPhoto.set((Uri) field(controller(activity), "photo"));
                });
                File previousOwned = ownedFile(previousPhoto.get());
                selectFrontFromGallery(scan, uri(replacement), true);
                await(scan, activity -> !controller(activity).isPhotoLoading()
                        && controller(activity).currentBitmap() != null
                        && controller(activity).currentBitmap() != previousBitmap.get()
                        && !previousPhoto.get().equals(field(controller(activity), "photo")));
                if (live) awaitCloud(scan);
                JSONObject replaced = stage(scan, baseline, database, store, historyId, history, historyIds, true);
                assertFalse("Replacing a front photo must remove the previous private copy", previousOwned.exists());
                replaced.put("previous_owned_front_deleted", true).put("front_bitmap_changed", true);
                assertNotEquals(initial.getString("owned_front_sha256"), replaced.getString("owned_front_sha256"));
                evidence.put("after_replacement", replaced);
                scan.onActivity(activity -> saveContent(activity, "result-front-replaced.png"));
                saveViewport("result-front-replaced-viewport.png");
                writeEvidence(evidence);

                AtomicReference<Uri> replacedPhoto = new AtomicReference<>();
                scan.onActivity(activity -> {
                    replacedPhoto.set((Uri) field(controller(activity), "photo"));
                    ViewGroup strip = activity.findViewById(R.id.photo_strip);
                    assertTrue(strip.getChildAt(1).findViewById(R.id.thumbnail_card).performClick());
                });
                clickPopup(scan, R.string.crosscheck_remove_photo);
                await(scan, activity -> controller(activity).currentBitmap() == null
                        && field(controller(activity), "photo") == null
                        && activity.findViewById(R.id.front_thumbnail_add_card) != null
                        && activity.findViewById(R.id.front_thumbnail_add_card).isShown());
                JSONObject deleted = stage(scan, baseline, database, store, historyId, history, historyIds, false);
                assertFalse("Deleting a front photo must remove its private copy", ownedFile(replacedPhoto.get()).exists());
                deleted.put("owned_front_deleted", true);
                evidence.put("after_deletion", deleted);
                scan.onActivity(activity -> {
                    assertNull(field(controller(activity), "estimate"));
                    assertEquals(View.GONE, activity.findViewById(R.id.food_crosscheck_panel).getVisibility());
                    saveContent(activity, "result-front-deleted.png");
                });
                saveViewport("result-front-deleted-viewport.png");
                evidence.put("passed", true);
                writeEvidence(evidence);
            }
        } finally {
            for (long id : store.liveIds()) if (!oldHistory.contains(id)) {
                store.delete(id);
                ScanHistoryImages.delete(historyImages, ScanHistoryImages.fileNameFor(id));
            }
            database.close();
            for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet()) {
                restore(context.getSharedPreferences(entry.getKey(), Context.MODE_PRIVATE), entry.getValue());
            }
        }
    }

    private JSONObject stage(ScanTestSupport.Scan scan, Baseline before, ScanHistoryDatabase database,
                             ScanHistoryStore store, long id, HistorySnapshot saved, Set<Long> savedIds,
                             boolean withFront) throws Exception {
        await(scan, activity -> pairReady(activity, withFront));
        AtomicReference<JSONObject> data = new AtomicReference<>();
        AtomicReference<FoodTypeCrossCheck> completedAdvice = new AtomicReference<>();
        scan.onActivity(activity -> {
            AnalysisReport after = (AnalysisReport) field(controller(activity), "report");
            assertSame("Front-photo changes must retain the original report", before.report, after);
            assertArrayEquals(before.bytes, bytes(after));
            assertEquals(before.labelUri, field(activity, "selectedPhoto"));
            assertSame(before.labelBitmap, bitmap(activity.findViewById(R.id.photo_preview)));
            FoodTypeEstimate currentEstimate = (FoodTypeEstimate) field(controller(activity), "estimate");
            FoodTypeCrossCheck currentAdvice = currentEstimate == null ? null
                    : FoodTypeCrossCheck.compare(after, currentEstimate);
            completedAdvice.set(currentAdvice);
            boolean hasReminders = withFront && currentAdvice != null && !currentAdvice.missingAllergens().isEmpty();
            if (!hasReminders) {
                assertEquals("Removing the photo or finding no mismatch must restore the label presentation",
                        before.rendered, visibleText(activity.findViewById(R.id.analysis_result)));
            } else {
                String mainSummary = ((TextView) activity.findViewById(R.id.verdict_message)).getText().toString();
                assertTrue(mainSummary.contains(currentAdvice.foodType()));
                assertTrue(mainSummary.contains("does not confirm"));
                String expectedTitle = after.overall() == AnalysisReport.Status.NOT_DETECTED
                        ? activity.getString(R.string.crosscheck_review_title)
                        : new AnalysisMessage(activity.getResources()).title(after);
                assertEquals(expectedTitle,
                        ((TextView) activity.findViewById(R.id.verdict_title)).getText().toString());
                ViewGroup rows = activity.findViewById(R.id.allergen_rows);
                for (com.example.allergycheck.analysis.Allergen allergen : currentAdvice.missingAllergens()) {
                    assertTrue(mainSummary.contains(allergen.label()));
                    View reviewRow = null;
                    for (int i = 0; i < rows.getChildCount(); i++) {
                        if (allergen.id().equals(rows.getChildAt(i).getTag(R.id.allergen_name))) reviewRow = rows.getChildAt(i);
                    }
                    assertNotNull("Missing allergens must appear in the main result", reviewRow);
                    assertEquals(activity.getString(R.string.crosscheck_review_status),
                            ((TextView) reviewRow.findViewById(R.id.allergen_status)).getText().toString());
                }
            }
            ViewGroup strip = activity.findViewById(R.id.photo_strip);
            assertEquals(2, strip.getChildCount());
            MaterialCardView labelCard = strip.getChildAt(0).findViewById(R.id.thumbnail_card);
            assertSame(before.labelBitmap, bitmap(labelCard.findViewById(R.id.thumbnail_image)));
            assertEquals(ImageView.ScaleType.FIT_CENTER, ((ImageView) labelCard.findViewById(R.id.thumbnail_image)).getScaleType());
            JSONObject geometry;
            if (withFront) {
                MaterialCardView frontCard = strip.getChildAt(1).findViewById(R.id.thumbnail_card);
                assertPair(labelCard, frontCard);
                assertSame(controller(activity).currentBitmap(), bitmap(frontCard.findViewById(R.id.thumbnail_image)));
                assertEquals(ImageView.ScaleType.FIT_CENTER, ((ImageView) frontCard.findViewById(R.id.thumbnail_image)).getScaleType());
                geometry = json("ingredient", geometry(labelCard), "front", geometry(frontCard));
            } else {
                assertNull(controller(activity).currentBitmap());
                View plus = strip.getChildAt(1).findViewById(R.id.front_thumbnail_add_card);
                assertNotNull(plus);
                assertTrue(plus.isShown());
                assertPair(labelCard, (MaterialCardView) plus);
                geometry = json("ingredient", geometry(labelCard), "add_front", geometry(plus));
            }
            JSONObject snapshot = json("pair", geometry, "report_same_instance", true,
                    "report_bytes_unchanged", true, "label_uri_unchanged", true,
                    "label_bitmap_unchanged", true, "main_result_shows_photo_reminders", hasReminders,
                    "main_result_restored_without_reminders", !hasReminders,
                    "thumbnail_bitmap_bindings_match", true, "cloud_executed", false);
            if (withFront) {
                Uri photo = (Uri) field(controller(activity), "photo");
                try { snapshot.put("owned_front_sha256", sha256(Files.readAllBytes(ownedFile(photo).toPath()))); }
                catch (Exception failure) { throw new AssertionError(failure); }
                FoodTypeEstimate estimate = (FoodTypeEstimate) field(controller(activity), "estimate");
                if (estimate != null) {
                    FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(after, estimate);
                    JSONArray common = new JSONArray(), missing = new JSONArray();
                    estimate.commonAllergens().forEach(a -> common.put(a.id()));
                    advice.missingAllergens().forEach(a -> missing.put(a.id()));
                    for (com.example.allergycheck.analysis.Allergen allergen : advice.missingAllergens()) {
                        assertEquals(AnalysisReport.Status.NOT_DETECTED, result(after, allergen.id()).status());
                        assertTrue(result(after, allergen.id()).findings().isEmpty());
                    }
                    snapshot = merge(snapshot, "cloud_executed", true, "food_type", estimate.foodType(),
                            "common_allergen_ids", common, "reminder_allergen_ids", missing,
                            "reminder_text", ((TextView) activity.findViewById(R.id.front_crosscheck_message)).getText().toString());
                }
            }
            data.set(snapshot);
        });
        if (completedAdvice.get() != null) lastCompletedAdvice = completedAdvice.get();
        awaitStoredAdvice(store, id, lastCompletedAdvice);
        HistorySnapshot after = history(database, id);
        assertArrayEquals(saved.report, after.report);
        assertEquals(saved.columns, after.columns);
        assertEquals(savedIds, store.liveIds());
        return data.get().put("report_sha256", sha256(before.bytes)).put("history_report_sha256", sha256(after.report))
                .put("history_label_columns_unchanged", true).put("history_rows_unchanged", true)
                .put("history_photo_reminders_saved", lastCompletedAdvice != null
                        && !lastCompletedAdvice.missingAllergens().isEmpty())
                .put("history_row_count", store.liveIds().size()).put("ingredient_report", reportJson(before.report));
    }

    private void selectFrontFromGallery(ScanTestSupport.Scan scan, Uri selected, boolean resultPage) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<Intent> launched = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!MediaStore.ACTION_PICK_IMAGES.equals(intent.getAction())) return null;
                launched.set(new Intent(intent));
                return new Instrumentation.ActivityResult(Activity.RESULT_OK,
                        new Intent().setData(selected).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            scan.onActivity(activity -> {
                View anchor = resultPage ? ((ViewGroup) activity.findViewById(R.id.photo_strip)).getChildAt(1)
                        .findViewById(R.id.thumbnail_card) : activity.findViewById(R.id.front_photo_add);
                assertNotNull(anchor);
                assertTrue(anchor.isShown());
                assertTrue(anchor.performClick());
            });
            clickPopup(scan, R.string.change_photo_choose);
            instrumentation.waitForIdleSync();
            assertNotNull("The photo action must launch the real image picker contract", launched.get());
            assertEquals("image/*", launched.get().getType());
        } finally { instrumentation.removeMonitor(monitor); }
    }

    /** Select a real native popup row via the public ListView/AdapterView event contract. */
    private static void clickPopup(ScanTestSupport.Scan scan, int textId) {
        await(scan, activity -> {
            for (View root : WindowInspector.getGlobalWindowViews()) {
                if (root == activity.getWindow().getDecorView()) continue;
                View text = findText(root, activity.getString(textId));
                if (text == null) continue;
                View row = text;
                for (ViewParent parent = row.getParent(); parent instanceof View; parent = row.getParent()) {
                    if (parent instanceof AdapterView) {
                        AdapterView<?> list = (AdapterView<?>) parent;
                        int position = list.getPositionForView(text);
                        assertTrue(position >= 0);
                        assertTrue(list.performItemClick(row, position, list.getItemIdAtPosition(position)));
                        return true;
                    }
                    row = (View) parent;
                }
            }
            return false;
        });
    }

    private static View findText(View view, String expected) {
        if (!view.isShown()) return null;
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findText(((ViewGroup) view).getChildAt(i), expected);
            if (found != null) return found;
        }
        return null;
    }

    private static void assertPair(MaterialCardView left, MaterialCardView right) {
        assertNotNull(left); assertNotNull(right);
        assertTrue(left.isShown()); assertTrue(right.isShown());
        assertTrue(left.getWidth() > 0); assertTrue(left.getHeight() > 0);
        assertTrue(Math.abs(left.getWidth() - right.getWidth()) <= 1);
        assertEquals(left.getHeight(), right.getHeight());
        int[] one = new int[2], two = new int[2];
        left.getLocationOnScreen(one); right.getLocationOnScreen(two);
        assertTrue(Math.abs(one[1] - two[1]) <= 2);
        assertTrue(one[0] + left.getWidth() <= two[0]);
        View viewport = left.getRootView();
        int[] window = new int[2]; viewport.getLocationOnScreen(window);
        assertTrue("Ingredient photo must be fully visible horizontally", one[0] >= window[0]);
        assertTrue("Front slot must fit inside the viewport", two[0] + right.getWidth() <= window[0] + viewport.getWidth());
        assertEquals(left.getRadius(), right.getRadius(), .1f);
        assertEquals(left.getStrokeWidth(), right.getStrokeWidth());
        assertEquals(left.getCardBackgroundColor().getDefaultColor(), right.getCardBackgroundColor().getDefaultColor());
    }

    private static boolean pairReady(MainActivity activity, boolean withFront) {
        ViewGroup strip = activity.findViewById(R.id.photo_strip);
        if (strip.getChildCount() != 2) return false;
        View left = strip.getChildAt(0).findViewById(R.id.thumbnail_card);
        View right = strip.getChildAt(1).findViewById(withFront ? R.id.thumbnail_card : R.id.front_thumbnail_add_card);
        return left != null && right != null && left.isShown() && right.isShown()
                && left.getWidth() > 0 && right.getWidth() > 0 && left.getHeight() > 0 && right.getHeight() > 0;
    }

    private static void awaitCloud(ScanTestSupport.Scan scan) {
        await(scan, activity -> {
            FoodCrossCheckController controller = controller(activity);
            if (field(controller, "estimate") != null) return true;
            String message = ((TextView) activity.findViewById(R.id.front_crosscheck_message)).getText().toString();
            assertFalse("Real cloud request could not complete: " + message, message.contains("could not"));
            return false;
        }, 120_000);
    }

    private static void await(ScanTestSupport.Scan scan, Predicate<MainActivity> condition) { await(scan, condition, 30_000); }
    private static void await(ScanTestSupport.Scan scan, Predicate<MainActivity> condition, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            boolean[] ready = {false};
            scan.onActivity(activity -> ready[0] = condition.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The real-photo UI did not reach the expected state");
    }

    private static final class Baseline {
        final AnalysisReport report;
        final byte[] bytes;
        final Object labelUri;
        final Bitmap labelBitmap;
        final String rendered;
        Baseline(MainActivity activity) {
            report = (AnalysisReport) field(controller(activity), "report");
            bytes = bytes(report);
            labelUri = field(activity, "selectedPhoto");
            labelBitmap = bitmap(activity.findViewById(R.id.photo_preview));
            rendered = visibleText(activity.findViewById(R.id.analysis_result));
        }
    }

    private static long awaitHistory(ScanHistoryStore store, Set<Long> before) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            for (long id : store.liveIds()) {
                ScanHistoryDetail detail = store.detail(id);
                if (!before.contains(id) && detail != null && detail.report() != null && detail.item().imageName() != null) return id;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The original label report was not saved with its photo");
    }

    private static HistorySnapshot history(ScanHistoryDatabase database, long id) {
        try (Cursor row = database.getReadableDatabase().rawQuery("SELECT * FROM scans WHERE _id=?", new String[]{Long.toString(id)})) {
            assertTrue(row.moveToFirst());
            byte[] bytes = row.getBlob(row.getColumnIndexOrThrow("report_blob"));
            assertNotNull(bytes);
            StringBuilder columns = new StringBuilder();
            for (int i = 0; i < row.getColumnCount(); i++) if (!"report_blob".equals(row.getColumnName(i))
                    && !"food_type_crosscheck".equals(row.getColumnName(i))) {
                columns.append(row.getColumnName(i)).append('=').append(row.isNull(i) ? "<null>" : row.getString(i)).append('\u001f');
            }
            return new HistorySnapshot(bytes, columns.toString());
        }
    }

    private static void awaitStoredAdvice(ScanHistoryStore store, long id, FoodTypeCrossCheck expected) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            ScanHistoryDetail detail = store.detail(id);
            FoodTypeCrossCheck saved = detail == null ? null : detail.foodTypeCrossCheck();
            if ((expected == null || expected.missingAllergens().isEmpty()) && saved == null) return;
            if (expected != null && saved != null && expected.foodType().equals(saved.foodType())
                    && expected.commonAllergens().equals(saved.commonAllergens())
                    && expected.missingAllergens().equals(saved.missingAllergens())) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The latest completed photo reminder was not retained in history");
    }
    private static final class HistorySnapshot {
        final byte[] report; final String columns;
        HistorySnapshot(byte[] report, String columns) { this.report = report; this.columns = columns; }
    }

    private static JSONObject reportJson(AnalysisReport report) throws Exception {
        JSONArray allergens = new JSONArray();
        for (AllergenResult result : report.results()) {
            JSONArray findings = new JSONArray();
            for (Finding finding : result.findings()) findings.put(new JSONObject().put("level", finding.level().name())
                    .put("source", finding.source().name()).put("reason", finding.reason().name()).put("evidence", finding.evidence()));
            allergens.put(new JSONObject().put("id", result.allergen().id()).put("status", result.status().name()).put("findings", findings));
        }
        return new JSONObject().put("overall", report.overall().name()).put("raw_text", report.label().rawText()).put("allergens", allergens);
    }

    private static AllergenResult result(AnalysisReport report, String id) {
        return report.results().stream().filter(r -> id.equals(r.allergen().id())).findFirst().orElseThrow(AssertionError::new);
    }
    private static Bitmap bitmap(ImageView view) { return ((BitmapDrawable) view.getDrawable()).getBitmap(); }
    private static FoodCrossCheckController controller(MainActivity activity) { return (FoodCrossCheckController) field(activity, "foodCrossCheck"); }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private Uri uri(File file) { return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file); }
    private File ownedFile(Uri uri) {
        assertNotNull(uri); assertNotNull(uri.getLastPathSegment());
        assertTrue(uri.getLastPathSegment().startsWith("front_photo_"));
        return new File(new File(context.getFilesDir(), "photos"), uri.getLastPathSegment());
    }
    private static File requiredFile(Bundle args, String key) {
        File file = new File(args.getString(key, ""));
        assertTrue("Missing prepared real photo: " + key, file.isFile());
        return file;
    }
    private static byte[] bytes(AnalysisReport report) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(report); }
            return bytes.toByteArray();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    private static String visibleText(View view) {
        if (view.getVisibility() != View.VISIBLE) return "";
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) text.append(visibleText(((ViewGroup) view).getChildAt(i)));
        return text.toString();
    }
    private static JSONObject geometry(View view) {
        int[] at = new int[2]; view.getLocationOnScreen(at);
        return json("x", at[0], "y", at[1], "width", view.getWidth(), "height", view.getHeight());
    }
    private static JSONObject json(Object... pairs) { return merge(new JSONObject(), pairs); }
    private static JSONObject merge(JSONObject object, Object... pairs) {
        try { for (int i = 0; i < pairs.length; i += 2) object.put((String) pairs[i], pairs[i + 1]); return object; }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private void writeEvidence(JSONObject evidence) throws Exception { Files.writeString(new File(artifacts, "evidence.json").toPath(), evidence.toString(2), StandardCharsets.UTF_8); }
    private void saveContent(MainActivity activity, String filename) {
        View content = activity.findViewById(R.id.content_column);
        Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(MaterialColors.getColor(content, com.google.android.material.R.attr.colorSurface));
        content.draw(canvas);
        saveBitmap(bitmap, filename);
    }
    private void saveViewport(String filename) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap); saveBitmap(bitmap, filename);
    }
    private void saveBitmap(Bitmap bitmap, String filename) {
        try (FileOutputStream output = new FileOutputStream(new File(artifacts, filename))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        catch (Exception failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }
    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, ?> old) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : old.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
        }
        assertTrue(editor.commit());
    }
}
