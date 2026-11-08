package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.history.ScanHistoryDatabase;
import com.example.allergycheck.history.ScanHistoryImages;
import com.example.allergycheck.history.ScanHistoryStore;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The history page end to end: what a row says, what opening one shows, and the delete paths.
 *
 * <p>Rows are seeded straight into the store rather than by running scans, so the assertions are
 * about the UI rather than about the recogniser. Rows created here are removed again afterwards
 * and any rows that existed before are left alone.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryUiTest {

    private Context context;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private File imageDirectory;
    private Set<Long> preexisting;
    private AllergenDictionary dictionary;

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        database = new ScanHistoryDatabase(context);
        imageDirectory = new File(context.getFilesDir(), "history");
        store = new ScanHistoryStore(database, imageDirectory);
        preexisting = new HashSet<>(store.liveIds());
        dictionary = shippedDictionary();
    }

    @After
    public void tearDown() {
        for (long id : newRowIds()) {
            store.delete(id);
            ScanHistoryImages.delete(imageDirectory, ScanHistoryImages.fileNameFor(id));
        }
        database.close();
    }

    @Test
    public void aRowNamesEveryDetectedAllergenWithAnOxfordComma() {
        long id = seed("Ingredients: wheat flour, milk powder, soya lecithin, sugar.");
        AnalysisReport report = store.detail(id).report();
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());

        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            String summary = awaitSummaryFor(scan, id);

            assertTrue("should end with 'detected': " + summary, summary.endsWith("detected"));
            // Three allergens means an Oxford comma before the conjunction.
            assertTrue("should join with 'and': " + summary, summary.contains(" and "));
            assertTrue("three names should be comma-separated: " + summary,
                    summary.contains(", "));
        }
    }

    @Test
    public void aCleanLabelReadsAsNoAllergensDetected() {
        long id = seed("Ingredients: water, sugar, citric acid, natural flavouring.");
        assertEquals(AnalysisReport.Status.NOT_DETECTED, store.detail(id).report().overall());

        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            assertEquals(context.getString(R.string.history_summary_none_detected),
                    awaitSummaryFor(scan, id));
        }
    }

    @Test
    public void aFailedReadShowsTheMessageItWasSavedWith() {
        long id = store.insertFailure(System.currentTimeMillis(), "",
                "Couldn't read this label", "No text was found in this photo.");

        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            assertEquals("Couldn't read this label", awaitSummaryFor(scan, id));
        }
    }

    @Test
    public void theEmptyStateShowsWhenThereIsNothingToList() {
        // Only meaningful on a device with no other history, so skip rather than wipe the user's.
        if (!preexisting.isEmpty()) {
            return;
        }
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            awaitTrue(scan, activity ->
                    activity.findViewById(R.id.history_empty).isShown()
                            && !activity.findViewById(R.id.clear_history_button).isShown(),
                    "the empty state should show and clear-all should be hidden");
        }
    }

    @Test
    public void theRetentionNoteIsVisible() {
        seed("Ingredients: milk.");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            awaitTrue(scan, activity ->
                            activity.findViewById(R.id.history_retention_note).isShown(),
                    "the 7-day note should be visible");
        }
    }

    @Test
    public void openingARowShowsTheFullResultAndBackReturnsToTheList() {
        long id = seed("Ingredients: wheat flour, milk powder, sugar.");

        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            awaitSummaryFor(scan, id);
            clickRowFor(scan, id);

            // The verdict is read from the detail fragment's own view, never from the activity:
            // the scan page has a copy of these ids.
            awaitTrue(scan, activity -> {
                View page = activity.findViewById(R.id.scan_detail_page);
                if (page == null || !page.isShown()) {
                    return false;
                }
                TextView verdict = page.findViewById(R.id.verdict_title);
                return verdict != null && verdict.getText().length() > 0;
            }, "the saved result should open");

            scan.onActivity(activity -> {
                View page = activity.findViewById(R.id.scan_detail_page);
                TextView verdict = page.findViewById(R.id.verdict_title);
                assertEquals(context.getString(R.string.status_contains_title),
                        verdict.getText().toString());
                ViewGroup rows = page.findViewById(R.id.allergen_rows);
                assertTrue("the per-allergen breakdown should be rendered",
                        rows.getChildCount() > 0);
            });

            scan.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            awaitTrue(scan, activity -> activity.findViewById(R.id.history_list) != null
                            && activity.findViewById(R.id.history_list).isShown(),
                    "back should return to the list");
        }
    }

    @Test
    public void longPressingARowOffersDeleteAndRemovesIt() {
        long id = seed("Ingredients: milk powder.");

        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            awaitSummaryFor(scan, id);

            scan.onActivity(activity -> {
                View row = rowFor(activity, id);
                assertNotNull(row);
                assertTrue(row.performLongClick());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            clickDialogButton(android.R.id.button1);

            awaitTrue(scan, activity -> !store.liveIds().contains(id),
                    "the row should have been deleted");
            assertFalse(store.liveIds().contains(id));
        }
    }

    @Test
    public void clearAllAsksFirstAndThenEmptiesTheList() {
        seed("Ingredients: milk powder.");
        seed("Ingredients: wheat flour.");

        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            awaitTrue(scan, activity ->
                            activity.findViewById(R.id.clear_history_button).isShown(),
                    "clear-all should be offered when there are rows");

            scan.onActivity(activity ->
                    assertTrue(activity.findViewById(R.id.clear_history_button).performClick()));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            // Cancel first: nothing should change.
            clickDialogButton(android.R.id.button2);
            SystemClock.sleep(300);
            assertFalse("cancelling must not delete anything", store.liveIds().isEmpty());

            scan.onActivity(activity ->
                    assertTrue(activity.findViewById(R.id.clear_history_button).performClick()));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            clickDialogButton(android.R.id.button1);

            awaitTrue(scan, activity -> store.count() == 0, "clear all should empty the table");
            awaitTrue(scan, activity -> activity.findViewById(R.id.history_empty).isShown(),
                    "the empty state should appear");
        }
    }


    @Test
    public void swipingARowOffersUndoWhichPutsItBack() {
        long id = seed("Ingredients: milk powder.");

        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            awaitSummaryFor(scan, id);

            swipeAway(scan, id);
            // The row leaves the list at once, but nothing is deleted until the Snackbar goes.
            awaitTrue(scan, activity -> !listed(activity, id),
                    "the row should leave the list immediately");
            assertTrue("the delete must not be written until the Snackbar is gone",
                    store.liveIds().contains(id));

            clickSnackbarAction();

            awaitTrue(scan, activity -> listed(activity, id),
                    "undo should put the row back");
            assertTrue("undo must leave the row in the database", store.liveIds().contains(id));
        }
    }

    @Test
    public void swipingARowAndLettingTheSnackbarGoDeletesIt() {
        long id = seed("Ingredients: milk powder.");

        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            awaitSummaryFor(scan, id);

            swipeAway(scan, id);
            awaitSnackbarGone();

            awaitTrue(scan, activity -> !store.liveIds().contains(id),
                    "the delete should be applied once the Snackbar is dismissed");
        }
    }

    @Test
    public void aPendingSwipeIsAppliedWhenLeavingTheHistoryPage() {
        long id = seed("Ingredients: milk powder.");

        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            openHistory(scan);
            awaitSummaryFor(scan, id);
            swipeAway(scan, id);

            // Switching away stops the fragment, which is where pending deletes are flushed. A row
            // that neither applied nor undid would otherwise reappear and read as a bug.
            scan.onActivity(activity -> {
                BottomNavigationView nav = activity.findViewById(R.id.bottom_nav);
                nav.setSelectedItemId(R.id.tab_scan);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            awaitTrue(scan, activity -> !store.liveIds().contains(id),
                    "leaving the page should apply the pending delete");

            openHistory(scan);
            SystemClock.sleep(500);
            awaitTrue(scan, activity -> !listed(activity, id),
                    "the deleted row must not come back");
        }
    }

    // ----- Helpers -----

    /**
     * Drives the swipe through {@code ItemTouchHelper}'s own callback rather than by synthesising
     * touch events, which is both flaky and dependent on where the row happens to sit.
     */
    private void swipeAway(ScanTestSupport.Scan scan, long id) {
        scan.onActivity(activity -> {
            androidx.recyclerview.widget.RecyclerView list =
                    activity.findViewById(R.id.history_list);
            androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                    list.findViewHolderForItemId(id);
            assertNotNull("the row should be on screen", holder);
            androidx.fragment.app.Fragment fragment =
                    activity.getSupportFragmentManager().findFragmentById(R.id.history_container);
            assertNotNull(fragment);
            int position = holder.getBindingAdapterPosition();
            ((HistoryFragment) fragment).onScanSwiped(position,
                    ((ScanHistoryAdapter) list.getAdapter()).itemAt(position));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void clickSnackbarAction() {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            boolean[] clicked = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (View root : rootViews()) {
                    View action = root.findViewById(
                            com.google.android.material.R.id.snackbar_action);
                    if (action != null && action.isShown()) {
                        clicked[0] = action.performClick();
                        return;
                    }
                }
            });
            if (clicked[0]) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No Snackbar action to click");
    }

    /**
     * Waits for the Snackbar to time out on its own. Nothing on the view exposes its Snackbar, and
     * letting LENGTH_LONG expire is exactly the path a user takes when they do not tap undo - so
     * this exercises the real dismissal callback rather than a stand-in for it.
     */
    private static void awaitSnackbarGone() {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        do {
            boolean[] showing = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (View root : rootViews()) {
                    View text = root.findViewById(
                            com.google.android.material.R.id.snackbar_text);
                    if (text != null && text.isShown()) {
                        showing[0] = true;
                        return;
                    }
                }
            });
            if (!showing[0]) {
                return;
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The Snackbar never went away");
    }


    private long seed(String labelText) {
        return store.insert(System.currentTimeMillis(),
                Analyzer.analyse(labelText, dictionary, dictionary.allergens()));
    }

    private Set<Long> newRowIds() {
        Set<Long> ids = new HashSet<>(store.liveIds());
        ids.removeAll(preexisting);
        return ids;
    }

    private static void openHistory(ScanTestSupport.Scan scan) {
        scan.onActivity(activity -> {
            BottomNavigationView nav = activity.findViewById(R.id.bottom_nav);
            nav.setSelectedItemId(R.id.tab_history);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static ScanHistoryAdapter adapterOf(MainActivity activity) {
        androidx.recyclerview.widget.RecyclerView list = activity.findViewById(R.id.history_list);
        return list == null ? null : (ScanHistoryAdapter) list.getAdapter();
    }

    /**
     * The row's position in the adapter, or -1. Presence is checked against the adapter's contents
     * rather than through {@code findViewHolderForItemId}, which keeps returning a holder while a
     * removal animation runs and for recycled holders that still carry the id.
     */
    private static int indexOf(MainActivity activity, long id) {
        ScanHistoryAdapter adapter = adapterOf(activity);
        if (adapter == null) {
            return -1;
        }
        for (int i = 0; i < adapter.size(); i++) {
            if (adapter.itemAt(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    private static boolean listed(MainActivity activity, long id) {
        return indexOf(activity, id) >= 0;
    }

    /** The row view for {@code id}, or null when it is not currently bound to one. */
    private View rowFor(MainActivity activity, long id) {
        androidx.recyclerview.widget.RecyclerView list = activity.findViewById(R.id.history_list);
        int position = indexOf(activity, id);
        if (list == null || position < 0) {
            return null;
        }
        androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                list.findViewHolderForAdapterPosition(position);
        return holder == null ? null : holder.itemView;
    }

    private String awaitSummaryFor(ScanTestSupport.Scan scan, long id) {
        List<String> found = new ArrayList<>();
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            found.clear();
            scan.onActivity(activity -> {
                View row = rowFor(activity, id);
                if (row != null) {
                    TextView summary = row.findViewById(R.id.scan_summary);
                    if (summary != null && summary.getText().length() > 0) {
                        found.add(summary.getText().toString());
                    }
                }
            });
            if (!found.isEmpty()) {
                return found.get(0);
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The seeded row never appeared in the list");
    }

    private void clickRowFor(ScanTestSupport.Scan scan, long id) {
        scan.onActivity(activity -> {
            View row = rowFor(activity, id);
            assertNotNull("the row should be on screen", row);
            assertTrue(row.performClick());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** Taps a button in whichever AlertDialog is showing. */
    private static void clickDialogButton(int buttonId) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
        });
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            boolean[] clicked = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (View root : rootViews()) {
                    View button = root.findViewById(buttonId);
                    if (button != null && button.isShown()) {
                        clicked[0] = button.performClick();
                        return;
                    }
                }
            });
            if (clicked[0]) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No dialog button " + buttonId + " to click");
    }

    /** Dialogs live in their own windows, so they are not under the activity's view root. */
    private static List<View> rootViews() {
        List<View> roots = new ArrayList<>();
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object instance = global.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field viewsField = global.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            Object views = viewsField.get(instance);
            if (views instanceof List) {
                for (Object view : (List<?>) views) {
                    if (view instanceof View) {
                        roots.add((View) view);
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not reach the window list", e);
        }
        return roots;
    }

    private interface Condition {
        boolean test(MainActivity activity);
    }

    private static void awaitTrue(ScanTestSupport.Scan scan, Condition condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            boolean[] ok = {false};
            scan.onActivity(activity -> ok[0] = condition.test(activity));
            if (ok[0]) {
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(message);
    }

    private AllergenDictionary shippedDictionary() throws IOException {
        int id = context.getResources().getIdentifier(
                "allergen_dictionary", "raw", context.getPackageName());
        assertTrue(id != 0);
        try (InputStream in = context.getResources().openRawResource(id);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return AllergenDictionary.load(reader);
        }
    }
}
