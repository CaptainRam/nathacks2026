package com.example.allergycheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.history.ScanHistoryDatabase;
import com.example.allergycheck.history.ScanHistoryDetail;
import com.example.allergycheck.history.ScanHistoryImages;
import com.example.allergycheck.history.ScanHistoryStore;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Real OCR and Settings results, with no front photo or cloud request. */
@RunWith(AndroidJUnit4.class)
public class SettingsResultIsolationTest {
    private Context context;
    private ScanTestSupport support;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private File historyImages;
    private Set<Long> preexisting;
    private final Map<String, Map<String, ?>> previous = new HashMap<>();

    @Before
    public void isolatePreferencesAndTrackExistingHistory() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[] {"allergy_preferences", "analysis_preferences", "analysis_credentials"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            previous.put(name, new HashMap<>(prefs.getAll()));
            assertTrue(prefs.edit().clear().commit());
        }
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
        AllergenPreferences.saveSelectedAllergenIds(context, Collections.singleton("MILK"));
        AllergenPreferences.saveMayContainRedWarning(context, true);
        support = new ScanTestSupport(context);
        database = new ScanHistoryDatabase(context);
        historyImages = new File(context.getFilesDir(), "history");
        store = new ScanHistoryStore(database, historyImages);
        preexisting = new HashSet<>(store.liveIds());
    }

    @After
    @SuppressWarnings("unchecked")
    public void restorePreferencesAndRemoveOnlyThisTestsHistory() {
        if (store != null && preexisting != null) {
            for (long id : newRows()) {
                store.delete(id);
                ScanHistoryImages.delete(historyImages, ScanHistoryImages.fileNameFor(id));
            }
        }
        if (database != null) database.close();
        if (support != null) support.close();
        for (Map.Entry<String, Map<String, ?>> file : previous.entrySet()) {
            SharedPreferences.Editor editor = context.getSharedPreferences(file.getKey(),
                    Context.MODE_PRIVATE).edit().clear();
            for (Map.Entry<String, ?> entry : file.getValue().entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof Set) editor.putStringSet(entry.getKey(),
                        new HashSet<>((Set<String>) value));
            }
            assertTrue(editor.commit());
        }
    }

    @Test
    public void visionAndWarningEditsKeepTheIngredientScanReportRowsAndHistory() throws Exception {
        File label = support.createLabel("INGREDIENTS: SUGAR, COCOA, SALT.", "MAY CONTAIN MILK.");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            support.loadPhoto(scan, label);
            ScanTestSupport.startCheck(scan);
            await(scan, activity -> activity.findViewById(R.id.analysis_result).isShown()
                    && field(controller(activity), "report") != null);
            AtomicReference<Snapshot> before = new AtomicReference<>();
            scan.onActivity(activity -> {
                AnalysisReport report = (AnalysisReport) field(controller(activity), "report");
                assertEquals("The real OCR fixture must include the precautionary milk statement",
                        AnalysisReport.Status.MAY_CONTAIN, report.overall());
                assertNull("No front photo means optional cloud inference cannot start",
                        controller(activity).currentBitmap());
                before.set(new Snapshot(activity, report));
            });
            long rowId = awaitSavedRowAndImage();
            byte[] savedReport = serialized(store.detail(rowId).report());
            Set<Long> savedIds = new HashSet<>(store.liveIds());
            String savedText = store.detail(rowId).rawText();
            String savedImage = store.detail(rowId).item().imageName();

            scan.onActivity(activity -> assertTrue(activity.findViewById(R.id.settings_button).performClick()));
            SettingsActivity visionSettings = awaitSettings();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ((MaterialButtonToggleGroup) visionSettings.findViewById(R.id.settings_section_toggle))
                        .check(R.id.section_model_button);
                ((EditText) visionSettings.findViewById(R.id.detection_api_key)).setText("synthetic-vision-key");
                ((EditText) visionSettings.findViewById(R.id.food_type_model_id)).setText("test/vision-only");
                assertTrue(visionSettings.findViewById(R.id.save_preferences_button).performClick());
                assertTrue("The valid optional vision draft must save", visionSettings.isFinishing());
            });
            awaitSettingsResult(scan);
            assertEquals("test/vision-only", AnalysisPreferences.foodTypeModel(context));
            assertEquals(AnalysisPreferences.Mode.MATCHING, AnalysisPreferences.mode(context));
            scan.onActivity(activity -> before.get().assertUnchanged(activity));
            assertHistoryUnchanged(rowId, savedIds, savedReport, savedText, savedImage);

            scan.onActivity(activity -> assertTrue(activity.findViewById(R.id.settings_button).performClick()));
            SettingsActivity warningSettings = awaitSettings();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                MaterialSwitch warning = warningSettings.findViewById(R.id.may_contain_red_warning_switch);
                assertTrue(warning.isChecked());
                assertTrue(warning.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertFalse(warning.isChecked());
                assertTrue(warningSettings.findViewById(R.id.save_preferences_button).performClick());
                assertTrue(warningSettings.isFinishing());
            });
            awaitSettingsResult(scan);
            assertFalse(AllergenPreferences.mayContainRedWarning(context));
            scan.onActivity(activity -> {
                before.get().assertUnchanged(activity);
                TextView status = before.get().row.findViewById(R.id.allergen_status);
                assertNotEquals("The display-only save must recolor the existing precautionary badge",
                        before.get().warningColor, status.getBackgroundTintList().getDefaultColor());
            });
            assertHistoryUnchanged(rowId, savedIds, savedReport, savedText, savedImage);
        }
    }

    private static final class Snapshot {
        final AnalysisReport report;
        final Object resultView;
        final int imageRequest;
        final Object photo;
        final View row;
        final int rowId;
        final int warningColor;
        final byte[] reportBytes;

        Snapshot(MainActivity activity, AnalysisReport report) {
            this.report = report;
            resultView = field(activity, "resultView");
            imageRequest = (int) field(activity, "imageRequest");
            photo = field(activity, "selectedPhoto");
            ViewGroup rows = activity.findViewById(R.id.allergen_rows);
            assertEquals(1, rows.getChildCount());
            row = rows.getChildAt(0);
            rowId = row.getId();
            TextView status = row.findViewById(R.id.allergen_status);
            assertNotNull(status.getBackgroundTintList());
            warningColor = status.getBackgroundTintList().getDefaultColor();
            reportBytes = serialized(report);
        }

        void assertUnchanged(MainActivity activity) {
            assertEquals("Display/vision edits must not restart ingredient OCR", imageRequest,
                    (int) field(activity, "imageRequest"));
            assertSame("Keep the exact ingredient report for the optional cross-check", report,
                    field(controller(activity), "report"));
            assertSame("Keep the displayed result renderer", resultView, field(activity, "resultView"));
            assertSame("Keep its cached report", report, field(resultView, "displayedReport"));
            assertArrayEquals("Preference edits cannot mutate ingredient findings", reportBytes,
                    serialized(report));
            assertEquals(photo, field(activity, "selectedPhoto"));
            ViewGroup rows = activity.findViewById(R.id.allergen_rows);
            assertEquals(1, rows.getChildCount());
            assertSame("Keep the same reading node for the milk row", row, rows.getChildAt(0));
            assertEquals(rowId, rows.getChildAt(0).getId());
            assertTrue(activity.findViewById(R.id.analysis_result).isShown());
            assertNull(controller(activity).currentBitmap());
        }
    }

    private void assertHistoryUnchanged(long id, Set<Long> ids, byte[] report,
                                        String text, String image) {
        assertEquals("No second scan row may be written by a Settings display refresh", ids,
                new HashSet<>(store.liveIds()));
        ScanHistoryDetail detail = store.detail(id);
        assertNotNull(detail);
        assertArrayEquals(report, serialized(detail.report()));
        assertEquals(text, detail.rawText());
        assertEquals(image, detail.item().imageName());
    }

    private long awaitSavedRowAndImage() {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            Set<Long> ids = newRows();
            assertTrue("The initial ingredient scan must create at most one row", ids.size() <= 1);
            if (ids.size() == 1) {
                long id = ids.iterator().next();
                ScanHistoryDetail detail = store.detail(id);
                if (detail != null && detail.report() != null && detail.item().imageName() != null) return id;
            }
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The original ingredient scan was not fully saved");
    }

    private Set<Long> newRows() {
        Set<Long> ids = new HashSet<>(store.liveIds());
        ids.removeAll(preexisting);
        return ids;
    }

    private static SettingsActivity awaitSettings() {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            AtomicReference<SettingsActivity> found = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof SettingsActivity && !activity.isFinishing()) {
                        found.set((SettingsActivity) activity);
                    }
                }
            });
            if (found.get() != null) return found.get();
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Settings did not resume from Main's launcher");
    }

    private static void awaitSettingsResult(ScanTestSupport.Scan scan) {
        await(scan, activity -> activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                && !(boolean) field(activity, "settingsOpen"));
    }

    private static FoodCrossCheckController controller(MainActivity activity) {
        return (FoodCrossCheckController) field(activity, "foodCrossCheck");
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static byte[] serialized(AnalysisReport report) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(report); }
            return bytes.toByteArray();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void await(ScanTestSupport.Scan scan, Predicate<MainActivity> predicate) {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        do {
            boolean[] ready = {false};
            scan.onActivity(activity -> ready[0] = predicate.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Settings and the ingredient result did not reach the expected state");
    }
}
