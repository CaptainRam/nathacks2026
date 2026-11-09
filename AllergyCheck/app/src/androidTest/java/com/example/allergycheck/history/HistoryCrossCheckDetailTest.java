package com.example.allergycheck.history;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.R;
import com.example.allergycheck.ScanDetailFragment;
import com.example.allergycheck.SettingsActivity;
import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.crosscheck.FoodTypeCrossCheck;
import com.example.allergycheck.crosscheck.FoodTypeEstimate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Saved reminders render offline through the same result view as a current scan. */
@RunWith(AndroidJUnit4.class)
public class HistoryCrossCheckDetailTest {
    private Context context;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private ScanHistory history;
    private ExecutorService worker;
    private Field instanceField;
    private Object previousHistory;
    private File images;
    private String databaseName;
    private SharedPreferences preferences;
    private Map<String, ?> previousPreferences;
    private Allergen milk;
    private AnalysisReport report;

    @Before
    public void isolateHistory() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("allergy_preferences", Context.MODE_PRIVATE);
        previousPreferences = new HashMap<>(preferences.getAll());
        assertTrue(preferences.edit().putBoolean("show_not_detected", false)
                .putBoolean("debug_label_text", false).commit());
        databaseName = "history_crosscheck_detail_" + UUID.randomUUID() + ".db";
        database = new ScanHistoryDatabase(context, databaseName);
        images = new File(context.getCacheDir(), "history_crosscheck_detail_" + UUID.randomUUID());
        assertTrue(images.mkdirs());
        store = new ScanHistoryStore(database, images);
        history = new ScanHistory(context, store, images);
        Field workerField = ScanHistory.class.getDeclaredField("worker");
        workerField.setAccessible(true);
        worker = (ExecutorService) workerField.get(history);
        instanceField = ScanHistory.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                previousHistory = instanceField.get(null);
                instanceField.set(null, history);
            } catch (IllegalAccessException exception) { throw new AssertionError(exception); }
        });
        AllergenDictionary dictionary;
        try (InputStream input = context.getResources().openRawResource(R.raw.allergen_dictionary)) {
            dictionary = AllergenDictionary.load(input);
        }
        milk = dictionary.byId("MILK");
        report = Analyzer.analyse("Ingredients: sugar, salt, cocoa.", dictionary,
                Collections.singletonList(milk));
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
    }

    @After
    @SuppressWarnings("unchecked")
    public void cleanUp() throws Exception {
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { instanceField.set(null, previousHistory); }
            catch (IllegalAccessException exception) { throw new AssertionError(exception); }
        });
        database.close();
        context.deleteDatabase(databaseName);
        images.delete();
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : previousPreferences.entrySet()) {
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

    @Test
    public void savedMismatchShowsMainWarningAndOriginalLabelStatusWithoutCloudSettings() throws Exception {
        long id = seed("chocolate cookies");
        try (ActivityScenario<SettingsActivity> screen = launchDetail(id)) {
            await(screen, activity -> ready(activity, R.string.crosscheck_review_title));
            screen.onActivity(activity -> {
                assertTrue(text(activity, R.id.verdict_message).contains("chocolate cookies"));
                assertTrue(text(activity, R.id.verdict_message).contains(milk.label()));
                assertTrue(text(activity, R.id.verdict_message).contains("does not confirm"));
                View row = onlyRow(activity);
                assertEquals(context.getString(R.string.crosscheck_review_status), text(row, R.id.allergen_status));
                assertTrue(row.performClick());
                assertEquals(View.VISIBLE, row.findViewById(R.id.allergen_evidence).getVisibility());
                assertTrue(text(row, R.id.allergen_evidence).contains("Ingredient label: Not detected."));
                assertTrue(text(row, R.id.allergen_evidence).contains("chocolate cookies"));
                TextView timestamp = activity.findViewById(R.id.detail_timestamp);
                assertTrue(timestamp.getAccessibilityPaneTitle().toString()
                        .contains(context.getString(R.string.crosscheck_review_title)));
            });
            captureIfRequested();
        }
        ScanHistoryDetail saved = store.detail(id);
        assertNotNull(saved.foodTypeCrossCheck());
        assertEquals(AnalysisReport.Status.NOT_DETECTED, saved.report().overall());
        assertTrue(saved.report().results().get(0).findings().isEmpty());
    }

    @Test
    public void returningRefreshesChangedSavedAdviceAndClearingRestoresOriginalResult() throws Exception {
        long id = seed("cookies");
        try (ActivityScenario<SettingsActivity> screen = launchDetail(id)) {
            await(screen, activity -> ready(activity, R.string.crosscheck_review_title));
            View[] row = new View[1];
            screen.onActivity(activity -> {
                row[0] = onlyRow(activity);
                assertTrue(row[0].performClick());
            });
            screen.moveToState(Lifecycle.State.CREATED);
            assertTrue(store.updateFoodTypeCrossCheck(id, advice("chocolate biscuits")));
            screen.moveToState(Lifecycle.State.RESUMED);
            await(screen, activity -> text(activity, R.id.verdict_message).contains("chocolate biscuits"));
            screen.onActivity(activity -> {
                assertSame("A saved reminder refresh retains the reading row", row[0], onlyRow(activity));
                assertEquals(View.VISIBLE, row[0].findViewById(R.id.allergen_evidence).getVisibility());
                assertTrue(text(row[0], R.id.allergen_evidence).contains("chocolate biscuits"));
            });

            screen.moveToState(Lifecycle.State.CREATED);
            assertTrue(store.updateFoodTypeCrossCheck(id, null));
            screen.moveToState(Lifecycle.State.RESUMED);
            await(screen, activity -> ready(activity, R.string.status_not_detected_title));
            screen.onActivity(activity -> {
                assertEquals(0, ((ViewGroup) activity.findViewById(R.id.allergen_rows)).getChildCount());
                assertFalse(text(activity, R.id.verdict_message).contains("chocolate biscuits"));
                assertTrue(((TextView) activity.findViewById(R.id.detail_timestamp))
                        .getAccessibilityPaneTitle().toString()
                        .contains(context.getString(R.string.status_not_detected_title)));
            });
        }
    }

    @Test
    public void completedPhotoCheckRefreshesAnAlreadyOpenDetailWithoutMovingReadingFocus() throws Exception {
        long id = store.insert(System.currentTimeMillis(), report);
        byte[] original = serialized(store.detail(id).report());
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo service = automation.getServiceInfo();
        int originalFlags = service.flags;
        try {
            service.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            automation.setServiceInfo(service);
            AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (!manager.isTouchExplorationEnabled() && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(25);
            }
            assertTrue(manager.isTouchExplorationEnabled());
            try (ActivityScenario<SettingsActivity> screen = launchDetail(id)) {
                await(screen, activity -> activity.hasWindowFocus()
                        && ready(activity, R.string.status_not_detected_title));
                automation.waitForIdle(100, 5000);
                View[] readingPosition = new View[1];
                View[] timestamp = new View[1];
                screen.onActivity(activity -> {
                    readingPosition[0] = activity.findViewById(R.id.detail_back_button);
                    timestamp[0] = activity.findViewById(R.id.detail_timestamp);
                    if (!readingPosition[0].isAccessibilityFocused()) {
                        assertTrue(readingPosition[0].performAccessibilityAction(
                                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
                    }
                    assertTrue(readingPosition[0].isAccessibilityFocused());
                    history.updateFoodTypeCrossCheck(id, advice("chocolate cookies"));
                });
                await(screen, activity -> ready(activity, R.string.crosscheck_review_title));
                screen.onActivity(activity -> {
                    assertEquals(Lifecycle.State.RESUMED, activity.getLifecycle().getCurrentState());
                    assertSame(timestamp[0], activity.findViewById(R.id.detail_timestamp));
                    assertTrue("A committed photo reminder must not move the reader from Back",
                            readingPosition[0].isAccessibilityFocused());
                    assertEquals(context.getString(R.string.crosscheck_review_status),
                            text(onlyRow(activity), R.id.allergen_status));
                    assertTrue(text(activity, R.id.verdict_message).contains("chocolate cookies"));
                });
                assertArrayEquals(original, serialized(store.detail(id).report()));
            }
        } finally {
            service.flags = originalFlags;
            automation.setServiceInfo(service);
        }
    }

    @Test
    public void displayPreferenceRefreshRetainsExpandedReminderAndRowsWithoutAdviceStayOrdinary() throws Exception {
        long id = seed("cookies");
        try (ActivityScenario<SettingsActivity> screen = launchDetail(id)) {
            await(screen, activity -> ready(activity, R.string.crosscheck_review_title));
            View[] row = new View[1];
            screen.onActivity(activity -> {
                row[0] = onlyRow(activity);
                assertTrue(row[0].performClick());
            });
            screen.moveToState(Lifecycle.State.CREATED);
            assertTrue(preferences.edit().putBoolean("debug_label_text", true).commit());
            screen.moveToState(Lifecycle.State.RESUMED);
            await(screen, activity -> activity.findViewById(R.id.ingredients_card).isShown());
            screen.onActivity(activity -> {
                assertSame(row[0], onlyRow(activity));
                assertEquals(View.VISIBLE, row[0].findViewById(R.id.allergen_evidence).getVisibility());
                assertEquals(context.getString(R.string.crosscheck_review_status), text(row[0], R.id.allergen_status));
            });
        }
        long ordinary = store.insert(System.currentTimeMillis(), report);
        try (ActivityScenario<SettingsActivity> screen = launchDetail(ordinary)) {
            await(screen, activity -> ready(activity, R.string.status_not_detected_title));
            screen.onActivity(activity -> assertEquals(0,
                    ((ViewGroup) activity.findViewById(R.id.allergen_rows)).getChildCount()));
        }
    }

    private long seed(String foodType) {
        long id = store.insert(System.currentTimeMillis(), report);
        assertTrue(store.updateFoodTypeCrossCheck(id, advice(foodType)));
        return id;
    }

    private FoodTypeCrossCheck advice(String foodType) {
        return FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate(foodType, Collections.singletonList(milk)));
    }

    private ActivityScenario<SettingsActivity> launchDetail(long id) {
        ActivityScenario<SettingsActivity> screen = ActivityScenario.launch(new Intent(context, SettingsActivity.class));
        screen.onActivity(activity -> {
            FrameLayout host = new FrameLayout(activity);
            host.setId(R.id.history_container);
            activity.setContentView(host, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ScanDetailFragment detail = new ScanDetailFragment();
            Bundle args = new Bundle();
            args.putLong("scan_id", id);
            detail.setArguments(args);
            activity.getSupportFragmentManager().beginTransaction()
                    .add(R.id.history_container, detail).commitNow();
        });
        return screen;
    }

    private void captureIfRequested() throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureHistoryCrossCheckResult"))) return;
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream out = new FileOutputStream(new File(context.getCacheDir(), "history-crosscheck-result.png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { screenshot.recycle(); }
    }

    private static View onlyRow(SettingsActivity activity) {
        ViewGroup rows = activity.findViewById(R.id.allergen_rows);
        assertEquals(1, rows.getChildCount());
        return rows.getChildAt(0);
    }

    private static byte[] serialized(AnalysisReport report) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(report); }
        return bytes.toByteArray();
    }

    private static String text(SettingsActivity activity, int id) { return text(activity.getWindow().getDecorView(), id); }
    private static String text(View root, int id) { return ((TextView) root.findViewById(id)).getText().toString(); }

    private static boolean ready(SettingsActivity activity, int title) {
        View result = activity.findViewById(R.id.history_result);
        return result != null && result.isShown() && activity.getString(title).equals(text(activity, R.id.verdict_title));
    }

    private static void await(ActivityScenario<SettingsActivity> screen, Predicate<SettingsActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            AtomicBoolean ready = new AtomicBoolean();
            screen.onActivity(activity -> ready.set(condition.test(activity)));
            if (ready.get()) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Saved detail did not reach the expected result state");
    }
}
