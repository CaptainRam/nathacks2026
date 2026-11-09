package com.example.allergycheck.history;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityManager;
import android.view.inspector.WindowInspector;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.HistoryFragment;
import com.example.allergycheck.R;
import com.example.allergycheck.SettingsActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Checks declarative reading fallbacks and real data changes against an isolated history store. */
@RunWith(AndroidJUnit4.class)
public class HistoryFocusAccessibilityTest {
    private Context context;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private ScanHistory history;
    private File images;
    private String databaseName;
    private Map<String, ?> originalPreferences;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalPreferences = new HashMap<>(context.getSharedPreferences(
                "allergy_preferences", Context.MODE_PRIVATE).getAll());
        databaseName = "history_focus_" + UUID.randomUUID() + ".db";
        database = new ScanHistoryDatabase(context, databaseName);
        images = new File(context.getCacheDir(), "history_focus_" + UUID.randomUUID());
        assertTrue(images.mkdirs());
        store = new ScanHistoryStore(database, images);
        history = new ScanHistory(context, store, images);
    }

    @After
    @SuppressWarnings("unchecked")
    public void tearDown() throws Exception {
        Field workerField = ScanHistory.class.getDeclaredField("worker");
        workerField.setAccessible(true);
        ExecutorService worker = (ExecutorService) workerField.get(history);
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        database.close();
        context.deleteDatabase(databaseName);
        images.delete();
        SharedPreferences.Editor editor = context.getSharedPreferences(
                "allergy_preferences", Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, ?> entry : originalPreferences.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>) value));
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else editor.putString(entry.getKey(), (String) value);
        }
        assertTrue(editor.commit());
    }

    @Test
    public void deletingLastScanOffersTheEmptyStateActionAsAFallback() {
        long id = seed(0);
        try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
            awaitRows(screen, 1);
            screen.onActivity(activity -> deleteNamedAction(row(activity, id)));
            confirmDelete();
            awaitRows(screen, 0);
            screen.onActivity(activity -> assertCandidate(activity,
                    activity.findViewById(R.id.history_empty_action)));
            assertEquals(0, store.count());
        }
    }

    @Test
    public void deletingARowOffersTheAdjacentSurvivingRowAsAFallback() {
        long older = seed(-1000);
        long newest = seed(0);
        try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
            awaitRows(screen, 2);
            screen.onActivity(activity -> deleteNamedAction(row(activity, newest)));
            confirmDelete();
            awaitRows(screen, 1);
            screen.onActivity(activity -> assertCandidate(activity, row(activity, older)));
            assertEquals(1, store.count());
        }
    }

    @Test
    public void cancellingNamedDeleteKeepsItsDataAndOffersTheOriginalRow() {
        long id = seed(0);
        try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
            awaitRows(screen, 1);
            screen.onActivity(activity -> deleteNamedAction(row(activity, id)));
            clickDialogButton(android.R.id.button2);
            awaitRows(screen, 1);
            screen.onActivity(activity -> assertCandidate(activity, row(activity, id)));
            assertEquals(1, store.count());
            assertTrue(store.liveIds().contains(id));
        }
    }

    @Test
    public void clearingHistoryOffersTheEmptyStateActionAsAFallback() {
        seed(0);
        seed(-1000);
        try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
            awaitRows(screen, 2);
            screen.onActivity(activity -> assertTrue(
                    activity.findViewById(R.id.clear_history_button).performClick()));
            confirmDelete();
            awaitRows(screen, 0);
            screen.onActivity(activity -> assertCandidate(activity,
                    activity.findViewById(R.id.history_empty_action)));
            assertEquals(0, store.count());
        }
    }

    @Test
    public void undoRestoresTheOriginalScanInOrderAndOffersThatScan() {
        verifySwipeUndoHint(false);
    }

    @Test
    public void undoAfterLeavingAndReturningDoesNotOfferAnObsoleteFallback() {
        verifySwipeUndoHint(true);
    }

    private void verifySwipeUndoHint(boolean leaveBeforeUndo) {
        long older = seed(-1000);
        long newest = seed(0);
        long originalTimestamp = store.detail(newest).item().createdAt();
        try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
            awaitRows(screen, 2);
            screen.onActivity(activity -> {
                HistoryFragment fragment = (HistoryFragment) activity.getSupportFragmentManager()
                        .findFragmentById(R.id.history_container);
                // Enter the same controller callback ItemTouchHelper uses after a completed swipe.
                fragment.onScanSwiped(0, store.detail(newest).item());
                if (leaveBeforeUndo) {
                    View page = fragment.requireView();
                    page.setVisibility(View.GONE);
                    try {
                        // Main explicitly revokes declarations when it hides this tab.
                        java.lang.reflect.Method cancel = HistoryFragment.class
                                .getDeclaredMethod("cancelPendingRemovalFocus");
                        cancel.setAccessible(true);
                        cancel.invoke(fragment);
                    } catch (ReflectiveOperationException exception) {
                        throw new AssertionError(exception);
                    }
                    page.setVisibility(View.VISIBLE);
                }
            });
            clickUndo();
            awaitRows(screen, 2);
            screen.onActivity(activity -> {
                if (leaveBeforeUndo) {
                    clearReadingPosition(activity.findViewById(R.id.history_page));
                    assertFalse("An Undo from an old reading position must not offer a new target",
                            row(activity, newest).createAccessibilityNodeInfo()
                                    .hasRequestInitialAccessibilityFocus());
                } else {
                    assertCandidate(activity, row(activity, newest));
                }
                assertEquals(context.getString(R.string.history_restored_title),
                        ((android.widget.TextView) activity.findViewById(R.id.history_load_title))
                                .getText().toString());
                RecyclerView list = activity.findViewById(R.id.history_list);
                assertEquals(newest, list.findViewHolderForAdapterPosition(0).getItemId());
                assertEquals(older, list.findViewHolderForAdapterPosition(1).getItemId());
            });
            assertEquals(2, store.count());
            assertEquals(originalTimestamp, store.detail(newest).item().createdAt());
        }
    }

    @Test
    public void choosingAnotherReadingPositionDuringDeletionSuppressesTheFallback() throws Exception {
        long older = seed(-1000);
        long newest = seed(0);
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        int originalFlags = info.flags;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            automation.setServiceInfo(info);
            AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
            await(manager::isTouchExplorationEnabled, "touch exploration should be available");
            try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
                awaitRows(screen, 2);
                Field field = ScanHistory.class.getDeclaredField("worker");
                field.setAccessible(true);
                ((ExecutorService) field.get(history)).execute(() -> {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                screen.onActivity(activity -> deleteNamedAction(row(activity, newest)));
                confirmDelete();
                awaitWindow(screen);
                screen.onActivity(activity -> {
                    View retention = activity.findViewById(R.id.history_retention_note);
                    if (!retention.isAccessibilityFocused()) {
                        assertTrue(retention.performAccessibilityAction(
                                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
                    }
                    assertTrue(retention.isAccessibilityFocused());
                    release.countDown();
                });
                awaitRows(screen, 1);
                SystemClock.sleep(400);
                screen.onActivity(activity -> {
                    assertTrue(activity.findViewById(R.id.history_retention_note).isAccessibilityFocused());
                    assertFalse("An existing reading position takes precedence over a fallback",
                            row(activity, older).createAccessibilityNodeInfo()
                                    .hasRequestInitialAccessibilityFocus());
                });
            }
        } finally {
            release.countDown();
            info.flags = originalFlags;
            automation.setServiceInfo(info);
        }
    }

    private static void assertCandidate(SettingsActivity activity, View target) {
        // The declaration is relevant when the reader has no existing position. Clear only the
        // temporary fixture's AX focus, then inspect the actual platform node's advisory flag.
        clearReadingPosition(activity.findViewById(R.id.history_page));
        assertTrue("The platform node must offer the expected initial reading candidate",
                target.createAccessibilityNodeInfo().hasRequestInitialAccessibilityFocus());
    }

    private static void clearReadingPosition(View view) {
        if (view.isAccessibilityFocused()) {
            view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                clearReadingPosition(group.getChildAt(index));
            }
        }
    }

    private static void clickUndo() {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            AtomicBoolean clicked = new AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    if (!root.hasWindowFocus()) continue;
                    View action = root.findViewById(com.google.android.material.R.id.snackbar_action);
                    if (action != null && action.isShown() && action.isEnabled()) {
                        clicked.set(action.performAccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLICK, null));
                        return;
                    }
                }
            });
            if (clicked.get()) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The swipe deletion did not offer an accessible Undo action");
    }

    private long seed(long offset) {
        return store.insertFailure(System.currentTimeMillis() + offset, "",
                "Test saved scan", "Test details");
    }

    private ActivityScenario<SettingsActivity> launchHistory() {
        ActivityScenario<SettingsActivity> screen = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class));
        screen.onActivity(activity -> {
            FrameLayout host = new FrameLayout(activity);
            host.setId(R.id.history_container);
            activity.setContentView(host, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TestHistoryFragment fragment = new TestHistoryFragment();
            fragment.isolatedHistory = history;
            activity.getSupportFragmentManager().beginTransaction()
                    .add(R.id.history_container, fragment).commitNow();
        });
        return screen;
    }

    public static class TestHistoryFragment extends HistoryFragment {
        ScanHistory isolatedHistory;

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            try {
                Field field = HistoryFragment.class.getDeclaredField("history");
                field.setAccessible(true);
                field.set(this, isolatedHistory);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private void awaitRows(ActivityScenario<SettingsActivity> screen, int expected) {
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            screen.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.history_list);
                ready.set(activity.hasWindowFocus() && list.getAdapter().getItemCount() == expected
                        && (expected == 0 ? activity.findViewById(R.id.history_empty).isShown()
                                : list.isShown() && list.getChildCount() == expected));
            });
            return ready.get();
        }, "history rows should finish loading and layout");
    }

    private static void awaitWindow(ActivityScenario<SettingsActivity> screen) {
        await(() -> {
            AtomicBoolean ready = new AtomicBoolean();
            screen.onActivity(activity -> ready.set(activity.hasWindowFocus()));
            return ready.get();
        }, "the history window should become active after the dialog");
    }

    private static View row(SettingsActivity activity, long id) {
        RecyclerView list = activity.findViewById(R.id.history_list);
        RecyclerView.ViewHolder holder = list.findViewHolderForItemId(id);
        assertNotNull(holder);
        return holder.itemView;
    }

    private void deleteNamedAction(View row) {
        for (AccessibilityNodeInfo.AccessibilityAction action : row.createAccessibilityNodeInfo().getActionList()) {
            if (action.getId() != AccessibilityNodeInfo.ACTION_LONG_CLICK
                    && context.getString(R.string.a11y_history_delete).contentEquals(
                            action.getLabel() == null ? "" : action.getLabel())) {
                assertTrue(row.performAccessibilityAction(action.getId(), null));
                return;
            }
        }
        throw new AssertionError("No named delete accessibility action on saved scan");
    }

    private static void confirmDelete() {
        clickDialogButton(android.R.id.button1);
    }

    private static void clickDialogButton(int buttonId) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        AtomicBoolean clicked = new AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) {
                View button = root.findViewById(buttonId);
                if (button != null && button.isShown()) {
                    clicked.set(button.performClick());
                    return;
                }
            }
        });
        assertTrue("the confirmation dialog should offer the requested action", clicked.get());
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(message);
    }
}
