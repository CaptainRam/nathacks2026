package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.history.ScanHistory;
import com.example.allergycheck.history.ScanHistoryDatabase;
import com.example.allergycheck.history.ScanHistoryImages;
import com.example.allergycheck.history.ScanHistoryStore;
import com.google.android.material.chip.Chip;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Missing home selection and scan re-check cases: SE-07/08 and F-12/13. No AI/network calls. */
@RunWith(AndroidJUnit4.class)
public class AllergenSelectionUiCoverageTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context context;
    private AllergenDictionary dictionary;
    private ScanTestSupport photos;
    private ScanHistoryDatabase database;
    private ScanHistoryStore history;
    private File historyImages;
    private Set<Long> preexistingIds;
    private final Map<SharedPreferences, Map<String, ?>> previous = new HashMap<>();

    @Before
    public void isolatePreferencesAndRememberHistory() {
        context = instrumentation.getTargetContext();
        dictionary = AllergenDictionaryProvider.get(context);
        for (String name : new String[]{"allergy_preferences", "analysis_preferences"}) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            Map<String, Object> snapshot = new HashMap<>();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                Object value = entry.getValue();
                snapshot.put(entry.getKey(), value instanceof Set
                        ? new HashSet<>((Set<?>) value) : value);
            }
            previous.put(preferences, snapshot);
            assertTrue(preferences.edit().clear().commit());
        }
        assertTrue(AllergenPreferences.preferences(context).edit()
                .putStringSet("selected_allergens", Collections.emptySet())
                .putBoolean("show_not_detected", true).commit());
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
        photos = new ScanTestSupport(context);
        database = new ScanHistoryDatabase(context);
        historyImages = new File(context.getFilesDir(), "history");
        history = new ScanHistoryStore(database, historyImages);
        preexistingIds = new HashSet<>(history.liveIds());
    }

    @After
    public void removeOnlyTestScansAndRestorePreferences() throws Exception {
        try {
            if (history != null) {
                CountDownLatch drained = new CountDownLatch(1);
                instrumentation.runOnMainSync(() -> ScanHistory.get(context)
                        .load(ScanHistory.DEFAULT_LIMIT, items -> drained.countDown()));
                assertTrue("History writes did not finish", drained.await(10, TimeUnit.SECONDS));
                Set<Long> added = new HashSet<>(history.liveIds());
                added.removeAll(preexistingIds);
                for (long id : added) {
                    history.delete(id);
                    ScanHistoryImages.delete(historyImages, ScanHistoryImages.fileNameFor(id));
                }
            }
        } finally {
            if (database != null) database.close();
            if (photos != null) photos.close();
            for (Map.Entry<SharedPreferences, Map<String, ?>> entry : previous.entrySet()) {
                restore(entry.getKey(), entry.getValue());
            }
        }
    }

    /** SE-07: draft ticks survive a real orientation change, and only Save persists them. */
    @Test
    public void homePickerRetainsUnsavedTicksAcrossRotationThenSavesThem() {
        saveSelection("MILK");
        try (Home home = Home.launch(context)) {
            openPicker(home, R.id.allergens_button);
            assertPickerChecked(home, "MILK", true);
            toggle(home, "PEANUTS");
            assertEquals(set("MILK"), savedSelection());

            home.rotate();

            awaitPicker(home);
            assertPickerChecked(home, "MILK", true);
            assertPickerChecked(home, "PEANUTS", true);
            assertEquals(set("MILK"), savedSelection());
            savePicker(home);
            awaitHomeSummary(home, "MILK", "PEANUTS");
            assertEquals(set("MILK", "PEANUTS"), savedSelection());
        }
    }

    /** SE-08: rendered count/chips resolve the same legacy, duplicate and unknown values. */
    @Test
    public void homeCountAndChipsMatchPrunedSelectionAndBecomeEmptyAfterSavingNone() {
        assertTrue(AllergenPreferences.preferences(context).edit().putStringSet(
                "selected_allergens", set("MILK", " milk ", " tree NUTS ", "REMOVED_ALLERGEN"))
                .commit());
        try (Home home = Home.launch(context)) {
            awaitHomeSummary(home, "MILK", "TREE_NUTS");
            assertEquals(set("MILK", "TREE_NUTS"), savedSelection());
            openPicker(home, R.id.allergens_button);
            toggle(home, "MILK");
            toggle(home, "TREE_NUTS");
            savePicker(home);
            awaitHomeSummary(home);
            assertTrue(savedSelection().isEmpty());
        }
    }

    /** F-12: the no-selection result offers the picker and saving checks the same photo again. */
    @Test
    public void emptySelectionResultOpensPickerAndSavingRechecksThePhoto() throws Exception {
        File photo = photos.createLabel("INGREDIENTS:", "Sugar, Milk Powder, Salt.");
        try (Home home = Home.launch(context)) {
            startScan(home, photo);
            awaitResult(home, R.string.status_no_allergens_title);
            home.onActivity(activity -> {
                TextView action = activity.findViewById(R.id.result_secondary_button);
                assertTrue(action.isShown());
                assertEquals(activity.getString(R.string.choose_allergens_action),
                        action.getText().toString());
            });
            openPicker(home, R.id.result_secondary_button);
            toggle(home, "MILK");
            savePicker(home);

            awaitResult(home, R.string.status_contains_title);
            // The verdict binds before the result-reveal animation finishes updating actions.
            await(home, activity -> {
                assertAllergenRow(activity, "MILK", R.string.result_contains);
                assertEquals(View.GONE,
                        activity.findViewById(R.id.result_secondary_button).getVisibility());
            }, "Updated result actions did not settle after selecting allergens");
            assertEquals(set("MILK"), savedSelection());
            assertEquals(photos.photoUri(photo).toString(), AllergenPreferences.preferences(context)
                    .getString("selected_photo", null));
        }
    }

    /** F-13: returning from Settings refreshes an existing result with the new selected set. */
    @Test
    public void changingSelectionInSettingsRecomputesTheVisibleResult() throws Exception {
        saveSelection("MILK");
        File photo = photos.createLabel("INGREDIENTS:", "Sugar, Milk Powder, Salt.");
        try (Home home = Home.launch(context)) {
            startScan(home, photo);
            awaitResult(home, R.string.status_contains_title);
            home.onActivity(activity -> assertAllergenRow(activity, "MILK", R.string.result_contains));

            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                    SettingsActivity.class.getName(), null, false);
            SettingsActivity settings = null;
            try {
                home.onActivity(activity -> assertTrue(
                        activity.findViewById(R.id.settings_button).performClick()));
                settings = (SettingsActivity) monitor.waitForActivityWithTimeout(10_000);
                assertNotNull("Settings did not open", settings);
                SettingsActivity opened = settings;
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> {
                    ViewGroup options = opened.findViewById(R.id.allergen_options_container);
                    Chip milk = (Chip) options.getChildAt(indexOf("MILK"));
                    Chip peanuts = (Chip) options.getChildAt(indexOf("PEANUTS"));
                    assertTrue(milk.isChecked());
                    assertFalse(peanuts.isChecked());
                    milk.performClick();
                    peanuts.performClick();
                    assertFalse(milk.isChecked());
                    assertTrue(peanuts.isChecked());
                    assertTrue(opened.findViewById(R.id.save_preferences_button).performClick());
                });
                awaitResult(home, R.string.status_not_detected_title);
                home.onActivity(activity -> assertAllergenRow(
                        activity, "PEANUTS", R.string.result_not_detected));
                assertEquals(set("PEANUTS"), savedSelection());
                assertEquals(photos.photoUri(photo).toString(), AllergenPreferences.preferences(context)
                        .getString("selected_photo", null));
            } finally {
                instrumentation.removeMonitor(monitor);
                SettingsActivity opened = settings;
                if (opened != null) instrumentation.runOnMainSync(() -> {
                    if (!opened.isFinishing() && !opened.isDestroyed()) opened.finish();
                });
            }
        }
    }

    @Test
    public void croppedPanelWithoutHeadingStillShowsTheAllergenAndConfidenceWarning() throws Exception {
        saveSelection("MILK");
        File photo = photos.createLabel("Milk Powder, Salt.");
        try (Home home = Home.launch(context)) {
            startScan(home, photo);
            awaitResult(home, R.string.status_contains_title);
            home.onActivity(activity -> {
                assertAllergenRow(activity, "MILK", R.string.result_contains);
                TextView note = activity.findViewById(R.id.low_confidence_note);
                assertTrue(note.isShown());
                assertEquals(activity.getString(R.string.low_confidence_warning), note.getText().toString());
            });
        }
    }

    private void startScan(Home home, File photo) {
        home.onActivity(activity -> ScanTestSupport.invoke(activity, "loadPhoto",
                new Class<?>[]{Uri.class}, photos.photoUri(photo)));
        await(home, activity -> {
            View button = activity.findViewById(R.id.start_analysis_button);
            assertTrue(button.isShown());
            assertTrue(button.isEnabled());
            assertNotNull(((ImageView) activity.findViewById(R.id.photo_preview)).getDrawable());
        }, "Photo did not finish loading");
        home.onActivity(activity -> assertTrue(
                activity.findViewById(R.id.start_analysis_button).performClick()));
    }

    private void awaitHomeSummary(Home home, String... ids) {
        await(home, activity -> {
            TextView button = activity.findViewById(R.id.allergens_button);
            assertEquals(ids.length == 0 ? activity.getString(R.string.set_allergens)
                            : activity.getResources().getQuantityString(
                                    R.plurals.allergens_selected, ids.length, ids.length),
                    button.getText().toString());
            ViewGroup chips = activity.findViewById(R.id.allergen_chips);
            assertEquals(ids.length, chips.getChildCount());
            assertEquals(ids.length == 0 ? View.GONE : View.VISIBLE, chips.getVisibility());
            List<String> expected = new ArrayList<>();
            Set<String> selected = set(ids);
            for (Allergen allergen : dictionary.allergens()) {
                if (selected.contains(allergen.id())) expected.add(allergen.label());
            }
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), ((TextView) chips.getChildAt(i)).getText().toString());
                assertFalse(chips.getChildAt(i).isClickable());
            }
        }, "Home count/chips did not match selection");
    }

    private static void awaitResult(Home home, int title) {
        await(home, activity -> {
            assertTrue(activity.findViewById(R.id.analysis_result).isShown());
            assertEquals(activity.getString(title),
                    ((TextView) activity.findViewById(R.id.verdict_title)).getText().toString());
        }, "The expected scan result did not appear");
    }

    private void assertAllergenRow(MainActivity activity, String id, int status) {
        ViewGroup rows = activity.findViewById(R.id.allergen_rows);
        assertEquals(1, rows.getChildCount());
        View row = rows.getChildAt(0);
        assertEquals(dictionary.byId(id).label(),
                ((TextView) row.findViewById(R.id.allergen_name)).getText().toString());
        assertEquals(activity.getString(status),
                ((TextView) row.findViewById(R.id.allergen_status)).getText().toString());
    }

    private static AlertDialog picker(MainActivity activity) {
        AllergenPreferencesDialogFragment fragment = (AllergenPreferencesDialogFragment)
                activity.getSupportFragmentManager().findFragmentByTag(
                        AllergenPreferencesDialogFragment.TAG);
        assertNotNull("Allergen picker did not open", fragment);
        return (AlertDialog) fragment.requireDialog();
    }

    private static void openPicker(Home home, int buttonId) {
        home.onActivity(activity -> assertTrue(activity.findViewById(buttonId).performClick()));
        awaitPicker(home);
    }

    private static void awaitPicker(Home home) {
        // AlertController applies the initial checked[] values while binding visible rows.
        // Reading them in the same main-thread turn as showNow() observes an unbound list.
        await(home, activity -> {
            ListView list = picker(activity).getListView();
            assertTrue(list.isShown());
            assertTrue(list.isLaidOut());
            assertTrue(list.getChildCount() > 0);
        }, "Allergen picker rows did not finish layout");
    }

    private void scrollToPickerItem(Home home, String id) {
        int index = indexOf(id);
        home.onActivity(activity -> picker(activity).getListView().setSelection(index));
        await(home, activity -> {
            ListView list = picker(activity).getListView();
            View row = list.getChildAt(index - list.getFirstVisiblePosition());
            assertNotNull("Picker row is not visible: " + id, row);
            assertTrue(row.isLaidOut());
        }, "Allergen picker did not display " + id);
    }

    private void assertPickerChecked(Home home, String id, boolean expected) {
        scrollToPickerItem(home, id);
        home.onActivity(activity -> assertEquals(expected,
                picker(activity).getListView().isItemChecked(indexOf(id))));
    }

    private void toggle(Home home, String id) {
        scrollToPickerItem(home, id);
        home.onActivity(activity -> {
            ListView list = picker(activity).getListView();
            int index = indexOf(id);
            boolean previouslyChecked = list.isItemChecked(index);
            View row = list.getChildAt(index - list.getFirstVisiblePosition());
            assertNotNull(row);
            assertTrue(list.performItemClick(row, index, list.getAdapter().getItemId(index)));
            assertEquals(!previouslyChecked, list.isItemChecked(index));
        });
    }

    private static void savePicker(Home home) {
        home.onActivity(activity -> assertTrue(picker(activity)
                .getButton(DialogInterface.BUTTON_POSITIVE).performClick()));
    }

    private int indexOf(String id) {
        for (int i = 0; i < dictionary.allergens().size(); i++) {
            if (dictionary.allergens().get(i).id().equals(id)) return i;
        }
        throw new AssertionError("No allergen " + id);
    }

    private void saveSelection(String... ids) {
        assertTrue(AllergenPreferences.preferences(context).edit()
                .putStringSet("selected_allergens", set(ids)).commit());
    }

    private Set<String> savedSelection() {
        return AllergenPreferences.selectedAllergenIds(context, dictionary);
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static void await(Home home, Consumer<MainActivity> assertion, String message) {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        AssertionError last = null;
        do {
            try {
                home.onActivity(assertion);
                return;
            } catch (AssertionError notReady) {
                last = notReady;
            }
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(message, last);
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
            else throw new AssertionError("Unexpected preference type for " + key);
        }
        assertTrue(editor.commit());
    }

    /** Existing MainActivity tests use a platform monitor so real rotation replaces this handle. */
    private static final class Home implements AutoCloseable {
        private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        private MainActivity activity;

        static Home launch(Context context) {
            Home home = new Home();
            home.activity = (MainActivity) home.instrumentation.startActivitySync(
                    new Intent(context, MainActivity.class).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            assertNotNull(home.activity);
            home.instrumentation.waitForIdleSync();
            await(home, current -> assertTrue(current.getLifecycle().getCurrentState()
                    .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)),
                    "MainActivity did not resume");
            return home;
        }

        void onActivity(Consumer<MainActivity> action) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                try {
                    action.accept(activity);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            if (failure.get() instanceof AssertionError) throw (AssertionError) failure.get();
            if (failure.get() instanceof RuntimeException) throw (RuntimeException) failure.get();
            if (failure.get() != null) throw new AssertionError(failure.get());
        }

        void rotate() {
            MainActivity old = activity;
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                    MainActivity.class.getName(), null, false);
            try {
                onActivity(current -> current.setRequestedOrientation(
                        current.getResources().getConfiguration().orientation
                                == Configuration.ORIENTATION_PORTRAIT
                                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
                activity = (MainActivity) monitor.waitForActivityWithTimeout(10_000);
                assertNotNull("Rotation did not recreate MainActivity", activity);
                assertNotSame(old, activity);
                instrumentation.waitForIdleSync();
                await(this, current -> {
                    assertTrue(old.isDestroyed());
                    assertTrue(current.getLifecycle().getCurrentState()
                            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED));
                }, "Rotated MainActivity did not resume");
            } finally {
                instrumentation.removeMonitor(monitor);
            }
        }

        @Override
        public void close() {
            if (activity != null) onActivity(MainActivity::finish);
            instrumentation.waitForIdleSync();
        }
    }
}
