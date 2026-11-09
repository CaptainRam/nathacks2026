package com.example.allergycheck.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.R;
import com.example.allergycheck.ScanDetailFragment;
import com.example.allergycheck.SettingsActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Real queued detail reads, including a callback for a view that has been recreated. */
@RunWith(AndroidJUnit4.class)
public class DetailAccessibilityTest {
    private Context context;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private ScanHistory history;
    private Object previousHistory;
    private Field instanceField;
    private ExecutorService worker;
    private File images;
    private String databaseName;
    private final List<CountDownLatch> gates = new ArrayList<>();

    @Before
    public void isolateHistory() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "detail_accessibility_" + UUID.randomUUID() + ".db";
        database = new ScanHistoryDatabase(context, databaseName);
        images = new File(context.getCacheDir(), "detail_accessibility_" + UUID.randomUUID());
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
            } catch (IllegalAccessException exception) {
                throw new AssertionError(exception);
            }
        });
    }

    @After
    public void cleanUp() throws Exception {
        for (CountDownLatch gate : gates) gate.countDown();
        try {
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    try {
                        instanceField.set(null, previousHistory);
                    } catch (IllegalAccessException exception) {
                        throw new AssertionError(exception);
                    }
                });
            } finally {
                database.close();
                context.deleteDatabase(databaseName);
                images.delete();
            }
        }
    }

    @Test
    public void loadingHeadingIsTheSameNodeAsTheReadyTimestamp() throws Exception {
        long id = seed("Saved fixture result");
        Gate gate = blockWorker();
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
        View[] originalHeading = new View[1];
        try (ActivityScenario<SettingsActivity> screen = launchDetail(id)) {
            screen.onActivity(activity -> {
                TextView heading = activity.findViewById(R.id.detail_timestamp);
                originalHeading[0] = heading;
                assertTrue(heading.isShown());
                assertEquals(context.getString(R.string.history_detail_loading),
                        heading.createAccessibilityNodeInfo().getText().toString());
                assertTrue(heading.isAccessibilityHeading());
                assertTrue(heading.isImportantForAccessibility());
                assertFalse("The page must not merge its static descendants",
                        activity.findViewById(R.id.scan_detail_page).isImportantForAccessibility());
                assertEquals(View.GONE, activity.findViewById(R.id.history_result).getVisibility());
            });
            gate.release.countDown();
            await(screen, activity -> ready(activity, "Saved fixture result"));
            screen.onActivity(activity -> {
                TextView heading = activity.findViewById(R.id.detail_timestamp);
                assertSame("Loading must update in place instead of destroying its reading node",
                        originalHeading[0], heading);
                assertTrue(heading.isShown());
                assertFalse(context.getString(R.string.history_detail_loading)
                        .contentEquals(heading.getText()));
                assertTrue(heading.getAccessibilityPaneTitle().toString().contains("Saved fixture result"));
            });
        }
    }

    @Test
    public void unavailableSavedScanUpdatesTheExistingHeading() throws Exception {
        Gate gate = blockWorker();
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
        View[] originalHeading = new View[1];
        try (ActivityScenario<SettingsActivity> screen = launchDetail(Long.MAX_VALUE)) {
            screen.onActivity(activity -> {
                assertTrue(isLoading(activity));
                originalHeading[0] = activity.findViewById(R.id.detail_timestamp);
            });
            gate.release.countDown();
            await(screen, activity -> ready(activity,
                    context.getString(R.string.history_report_unavailable_title)));
            screen.onActivity(activity -> {
                TextView heading = activity.findViewById(R.id.detail_timestamp);
                assertSame(originalHeading[0], heading);
                assertEquals(context.getString(R.string.history_report_unavailable_title),
                        heading.getText().toString());
                assertEquals(context.getString(R.string.history_report_unavailable_title),
                        heading.getAccessibilityPaneTitle().toString());
            });
        }
    }

    @Test
    public void focusedLoadingStatusContinuesAtTheReadyTimestamp() throws Exception {
        verifyRealAccessibilityFocus(true, false, false, false);
    }

    @Test
    public void readingBackDuringLoadingKeepsItsActualAccessibilityFocus() throws Exception {
        verifyRealAccessibilityFocus(false, false, false, false);
    }

    @Test
    public void freshlyFocusedLoadingAfterPauseContinuesAtTheReadyTimestamp() throws Exception {
        verifyRealAccessibilityFocus(true, true, false, false);
    }

    @Test
    public void aNewReadingPositionChosenDuringLoadingKeepsItsFocus() throws Exception {
        verifyRealAccessibilityFocus(true, false, true, false);
    }

    @Test
    public void focusedLoadingHeadingStaysFocusedWhenTheScanIsUnavailable() throws Exception {
        verifyRealAccessibilityFocus(true, false, false, true);
    }

    private void verifyRealAccessibilityFocus(boolean focusLoading, boolean pauseBeforeFreshFocus,
                                              boolean moveToBack, boolean unavailable) throws Exception {
        String resultTitle = unavailable ? context.getString(R.string.history_report_unavailable_title)
                : "Ready focus fixture";
        long id = unavailable ? Long.MAX_VALUE : seed(resultTitle);
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        int originalFlags = info.flags;
        Gate gate = blockWorker();
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
        try {
            // Use real View accessibility focus while keeping any existing TalkBack service
            // running. This changes only the temporary test-service flags, restored below.
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            automation.setServiceInfo(info);
            AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (!manager.isTouchExplorationEnabled() && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(25);
            }
            assertTrue(manager.isTouchExplorationEnabled());
            View[] originalHeading = new View[1];
            try (ActivityScenario<SettingsActivity> screen = launchDetail(id)) {
                await(screen, activity -> activity.hasWindowFocus() && isLoading(activity));
                if (pauseBeforeFreshFocus) {
                    View[] originalPage = new View[1];
                    screen.onActivity(activity -> originalPage[0] =
                            activity.findViewById(R.id.scan_detail_page));
                    screen.moveToState(Lifecycle.State.STARTED);
                    screen.moveToState(Lifecycle.State.RESUMED);
                    await(screen, activity -> activity.hasWindowFocus() && isLoading(activity));
                    screen.onActivity(activity -> assertSame(
                            "Pause/resume must retain this same pending detail view",
                            originalPage[0], activity.findViewById(R.id.scan_detail_page)));
                }
                // A resumed TalkBack window may still restore its previous reading position.
                // Establish an idle window before explicitly choosing a fresh Loading position.
                automation.waitForIdle(pauseBeforeFreshFocus ? 500 : 100, 5000);
                screen.onActivity(activity -> {
                    originalHeading[0] = activity.findViewById(R.id.detail_timestamp);
                    View source = activity.findViewById(focusLoading
                            ? R.id.detail_timestamp : R.id.detail_back_button);
                    if (pauseBeforeFreshFocus && source.isAccessibilityFocused()) {
                        assertTrue(source.performAccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null));
                    }
                    focus(source);
                    if (moveToBack) focus(activity.findViewById(R.id.detail_back_button));
                    gate.release.countDown();
                });
                await(screen, activity -> ready(activity, resultTitle));
                // Observe beyond the former delayed focus action. No delegate fakes successful
                // focus here: these are the actual View accessibility-focus flags.
                SystemClock.sleep(500);
                screen.onActivity(activity -> {
                    assertSame(originalHeading[0], activity.findViewById(R.id.detail_timestamp));
                    int expectedId = focusLoading && !moveToBack
                            ? R.id.detail_timestamp : R.id.detail_back_button;
                    assertTrue("Completing the detail read must preserve the user's reading node",
                            activity.findViewById(expectedId).isAccessibilityFocused());
                });
            }
        } finally {
            gate.release.countDown();
            info.flags = originalFlags;
            automation.setServiceInfo(info);
        }
    }

    private static void focus(View source) {
        if (!source.isAccessibilityFocused()) {
            assertTrue(source.performAccessibilityAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        }
        assertTrue("The source must have real View accessibility focus",
                source.isAccessibilityFocused());
    }

    @Test
    public void anOldViewCallbackCannotDismissTheNewViewsLoadingState() throws Exception {
        long oldId = seed("Old view result");
        long newId = seed("New view result");
        Gate first = blockWorker();
        assertTrue(first.entered.await(5, TimeUnit.SECONDS));
        try (ActivityScenario<SettingsActivity> screen = launchDetail(oldId)) {
            // The old detail read is already queued. Hold the following worker slot so its callback
            // can be inspected before the recreated view's own detail read is allowed to finish.
            Gate second = blockWorker();
            screen.onActivity(activity -> {
                FragmentManager manager = activity.getSupportFragmentManager();
                ScanDetailFragment detail = (ScanDetailFragment) manager.findFragmentById(R.id.history_container);
                View oldView = detail.requireView();
                manager.beginTransaction().detach(detail).commitNow();
                detail.requireArguments().putLong("scan_id", newId);
                manager.beginTransaction().attach(detail).commitNow();
                assertNotSame(oldView, detail.requireView());
            });
            first.release.countDown();
            assertTrue(second.entered.await(5, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                assertTrue("An obsolete callback must leave the new view loading",
                        isLoading(activity));
                assertEquals(View.GONE, activity.findViewById(R.id.history_result).getVisibility());
            });
            second.release.countDown();
            await(screen, activity -> ready(activity, "New view result"));
        }
    }

    private long seed(String title) {
        return store.insertFailure(System.currentTimeMillis(), "", title, "Saved fixture body");
    }

    private ActivityScenario<SettingsActivity> launchDetail(long id) {
        ActivityScenario<SettingsActivity> screen = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class));
        screen.onActivity(activity -> {
            FrameLayout host = new FrameLayout(activity);
            host.setId(R.id.history_container);
            activity.setContentView(host, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ScanDetailFragment detail = new ScanDetailFragment();
            Bundle arguments = new Bundle();
            arguments.putLong("scan_id", id);
            detail.setArguments(arguments);
            activity.getSupportFragmentManager().beginTransaction()
                    .add(R.id.history_container, detail).commitNow();
        });
        return screen;
    }

    private Gate blockWorker() {
        Gate gate = new Gate();
        gates.add(gate.release);
        worker.execute(() -> {
            gate.entered.countDown();
            try {
                gate.release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        return gate;
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private static boolean isLoading(SettingsActivity activity) {
        TextView heading = activity.findViewById(R.id.detail_timestamp);
        return heading.isShown() && activity.getString(R.string.history_detail_loading)
                .contentEquals(heading.getText());
    }

    private static boolean ready(SettingsActivity activity, String title) {
        return !isLoading(activity) && activity.findViewById(R.id.history_result).isShown()
                && title.contentEquals(((TextView) activity.findViewById(R.id.verdict_title)).getText());
    }

    private static void await(ActivityScenario<SettingsActivity> screen, Predicate<SettingsActivity> ready) {
        await(screen, ready, "expected loading/result state");
    }

    private static void await(ActivityScenario<SettingsActivity> screen, Predicate<SettingsActivity> ready,
                              String condition) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            AtomicBoolean result = new AtomicBoolean();
            screen.onActivity(activity -> result.set(ready.test(activity)));
            if (result.get()) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        String[] diagnostic = {"unavailable"};
        screen.onActivity(activity -> diagnostic[0] = detailDiagnostic(activity));
        throw new AssertionError("Detail did not reach " + condition + ". " + diagnostic[0]);
    }

    /** Failure-only state: synthetic fixture labels and lifecycle/focus metadata, never prefs. */
    private static String detailDiagnostic(SettingsActivity activity) {
        View page = activity.findViewById(R.id.scan_detail_page);
        ScanDetailFragment fragment = (ScanDetailFragment) activity.getSupportFragmentManager()
                .findFragmentById(R.id.history_container);
        if (page == null || fragment == null) return "page=" + (page != null) + ", fragment=" + fragment;
        View timestamp = page.findViewById(R.id.detail_timestamp);
        View back = page.findViewById(R.id.detail_back_button);
        View focusedInPage = accessibilityFocusedView(page);
        View focusedInWindow = accessibilityFocusedView(activity.getWindow().getDecorView());
        return "activityState=" + activity.getLifecycle().getCurrentState()
                + ", fragmentState=" + fragment.getLifecycle().getCurrentState()
                + ", resumed=" + fragment.isResumed() + ", visible=" + fragment.isVisible()
                + ", pageShown=" + page.isShown() + ", windowFocus=" + page.hasWindowFocus()
                + ", currentView=" + (fragment.getView() == page)
                + ", currentFragment=" + (activity.getSupportFragmentManager()
                        .findFragmentById(fragment.getId()) == fragment)
                + ", timestampFocused=" + timestamp.isAccessibilityFocused()
                + ", backFocused=" + back.isAccessibilityFocused()
                + ", headingText=" + ((TextView) timestamp).getText()
                + ", focusedInPage=" + describeFocusedView(focusedInPage, true)
                + ", focusedInWindow=" + describeFocusedView(focusedInWindow, false);
    }

    private static View accessibilityFocusedView(View view) {
        if (!view.isShown()) return null;
        if (view.isAccessibilityFocused()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View focused = accessibilityFocusedView(group.getChildAt(index));
                if (focused != null) return focused;
            }
        }
        return null;
    }

    private static String describeFocusedView(View view, boolean includeFixtureText) {
        if (view == null) return "none";
        String id = view.getId() == View.NO_ID ? "no-id" : String.valueOf(view.getId());
        try { if (view.getId() != View.NO_ID) id = view.getResources().getResourceName(view.getId()); }
        catch (android.content.res.Resources.NotFoundException ignored) { }
        String text = includeFixtureText && view instanceof TextView
                ? ((TextView) view).getText().toString() : "";
        if (text.length() > 160) text = text.substring(0, 160);
        return id + "(" + view.getClass().getSimpleName() + ")" + (text.isEmpty() ? "" : " text=" + text);
    }
}
