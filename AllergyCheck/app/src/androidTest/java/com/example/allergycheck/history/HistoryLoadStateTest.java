package com.example.allergycheck.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.widget.NestedScrollView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Genuine isolated SQLite failures and a held worker, without changing the user's history. */
@RunWith(AndroidJUnit4.class)
public class HistoryLoadStateTest {
    private Context context;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private ScanHistory history;
    private ExecutorService worker;
    private File images;
    private String databaseName;
    private final CountDownLatch releaseWorker = new CountDownLatch(1);

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "history_load_" + UUID.randomUUID() + ".db";
        database = new ScanHistoryDatabase(context, databaseName);
        images = new File(context.getCacheDir(), "history_load_" + UUID.randomUUID());
        assertTrue(images.mkdirs());
        store = new ScanHistoryStore(database, images);
        history = new ScanHistory(context, store, images);
        Field field = ScanHistory.class.getDeclaredField("worker");
        field.setAccessible(true);
        worker = (ExecutorService) field.get(history);
    }

    @After
    public void tearDown() throws Exception {
        releaseWorker.countDown();
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        database.close();
        context.deleteDatabase(databaseName);
        images.delete();
    }

    @Test
    public void loadingHasANamedPoliteStatusAndNoEmptyOrDestructiveActions() throws Exception {
        seed();
        CountDownLatch blocked = new CountDownLatch(1);
        worker.execute(() -> {
            blocked.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
            screen.onActivity(activity -> {
                TextView title = activity.findViewById(R.id.history_load_title);
                assertTrue(title.isShown());
                assertEquals(context.getString(R.string.history_loading), title.getText().toString());
                assertTrue(title.isAccessibilityHeading());
                assertFalse(activity.findViewById(R.id.history_page).isImportantForAccessibility());
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, title.getAccessibilityLiveRegion());
                assertHidden(activity, R.id.history_empty, R.id.history_list,
                        R.id.clear_history_button, R.id.history_load_retry);
                assertStatusReadingTargets(activity.findViewById(R.id.history_page), false);
            });
            releaseWorker.countDown();
            await(screen, activity -> activity.findViewById(R.id.history_list).isShown());
            screen.onActivity(activity -> assertEquals(1, rows(activity)));
        }
    }

    @Test
    public void loadedRowsKeepTheExistingFocusedHeader() throws Exception {
        verifyLoadingFocus(false);
    }

    @Test
    public void freshlyFocusedLoadingAfterPauseKeepsTheSameHeader() throws Exception {
        verifyLoadingFocus(true);
    }

    private void verifyLoadingFocus(boolean pauseBeforeFreshFocus) throws Exception {
        seed();
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        int originalFlags = info.flags;
        CountDownLatch secondRelease = new CountDownLatch(1);
        try {
            // Real View accessibility focus requires touch exploration. Request it through the
            // test service while preserving any running TalkBack service, then restore its flags.
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            automation.setServiceInfo(info);
            AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (!manager.isTouchExplorationEnabled() && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(25);
            }
            assertTrue(manager.isTouchExplorationEnabled());
            blockWorker(releaseWorker);
            try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
                await(screen, activity -> activity.hasWindowFocus()
                        && activity.findViewById(R.id.history_load_title).isShown());
                if (pauseBeforeFreshFocus) {
                    View[] originalPage = new View[1];
                    screen.onActivity(activity -> originalPage[0] = activity.findViewById(R.id.history_page));
                    screen.moveToState(Lifecycle.State.STARTED);
                    screen.moveToState(Lifecycle.State.RESUMED);
                    await(screen, activity -> activity.hasWindowFocus()
                            && activity.findViewById(R.id.history_load_title).isShown());
                    screen.onActivity(activity -> assertSame(
                            "Pause/resume must retain this pending history view",
                            originalPage[0], activity.findViewById(R.id.history_page)));
                }
                automation.waitForIdle(100, 3000);
                View[] loadingHeader = new View[1];
                screen.onActivity(activity -> {
                    View title = activity.findViewById(R.id.history_load_title);
                    loadingHeader[0] = title;
                    if (pauseBeforeFreshFocus && title.isAccessibilityFocused()) {
                        assertTrue(title.performAccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null));
                    }
                    if (!title.isAccessibilityFocused()) {
                        assertTrue(title.performAccessibilityAction(
                                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
                    }
                    assertTrue(title.isAccessibilityFocused());
                    releaseWorker.countDown();
                });
                await(screen, activity -> activity.findViewById(R.id.history_list).isShown());
                SystemClock.sleep(400);
                screen.onActivity(activity -> {
                    assertEquals(1, rows(activity));
                    View readyHeader = activity.findViewById(R.id.history_load_title);
                    assertSame("The loading reader must retain the same accessibility node",
                            loadingHeader[0], readyHeader);
                    assertTrue(readyHeader.isAccessibilityFocused());
                    assertEquals(context.getString(R.string.history_title),
                            ((TextView) readyHeader).getText().toString());
                    RecyclerView list = activity.findViewById(R.id.history_list);
                    assertFalse("Ordinary loading completion must not nominate the first row",
                            list.getChildAt(0).createAccessibilityNodeInfo()
                                    .hasRequestInitialAccessibilityFocus());
                });

                if (pauseBeforeFreshFocus) return;

                // Repeat with a stable, visible reading position outside the loading panel.
                // Completing another load must not redirect that reader to the list.
                blockWorker(secondRelease);
                screen.moveToState(Lifecycle.State.CREATED);
                screen.moveToState(Lifecycle.State.RESUMED);
                await(screen, activity -> activity.hasWindowFocus()
                        && activity.findViewById(R.id.history_load_title).isShown());
                screen.onActivity(activity -> {
                    View retention = activity.findViewById(R.id.history_retention_note);
                    if (!retention.isAccessibilityFocused()) {
                        assertTrue(retention.performAccessibilityAction(
                                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
                    }
                    assertTrue(retention.isAccessibilityFocused());
                    secondRelease.countDown();
                });
                await(screen, activity -> activity.findViewById(R.id.history_list).isShown());
                SystemClock.sleep(400);
                screen.onActivity(activity -> assertTrue(activity.findViewById(
                        R.id.history_retention_note).isAccessibilityFocused()));
            }
        } finally {
            releaseWorker.countDown();
            secondRelease.countDown();
            info.flags = originalFlags;
            automation.setServiceInfo(info);
        }
    }

    private void blockWorker(CountDownLatch release) throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        worker.execute(() -> {
            blocked.countDown();
            try { release.await(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void queryFailureIsNotEmptyAndRetryRecoversTheExistingScan() {
        seed();
        SQLiteDatabase sqlite = database.getWritableDatabase();
        sqlite.execSQL("ALTER TABLE scans RENAME TO unreadable_scans");
        try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
            awaitFailure(screen);
            // A real saved record still exists. Only its normal query name is unavailable.
            try (Cursor cursor = sqlite.rawQuery("SELECT COUNT(*) FROM unreadable_scans", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(1, cursor.getInt(0));
            }
            sqlite.execSQL("ALTER TABLE unreadable_scans RENAME TO scans");
            screen.onActivity(activity -> assertTrue(activity.findViewById(R.id.history_load_retry)
                    .performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)));
            await(screen, activity -> activity.findViewById(R.id.history_list).isShown());
            screen.onActivity(activity -> {
                assertEquals(1, rows(activity));
                assertTrue(activity.findViewById(R.id.clear_history_button).isShown());
                assertHidden(activity, R.id.history_empty, R.id.history_load_state);
            });
        }
    }

    @Test
    public void failedRefreshHidesCachedRowsAndClearAllRatherThanClaimingAnEmptyHistory() {
        seed();
        try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
            await(screen, activity -> activity.findViewById(R.id.history_list).isShown());
            database.getWritableDatabase().execSQL("ALTER TABLE scans RENAME TO unreadable_scans");
            screen.moveToState(Lifecycle.State.CREATED);
            screen.moveToState(Lifecycle.State.RESUMED);
            awaitFailure(screen);
            screen.onActivity(activity -> assertEquals(
                    "A failed query must not discard the cached successful dataset", 1, rows(activity)));
        }
    }

    @Test
    public void successfulEmptyQueryShowsTheActualEmptyState() {
        try (ActivityScenario<SettingsActivity> screen = launchHistory()) {
            await(screen, activity -> activity.findViewById(R.id.history_empty).isShown());
            screen.onActivity(activity -> {
                assertEquals(0, rows(activity));
                assertHidden(activity, R.id.history_load_state, R.id.history_load_retry,
                        R.id.history_list, R.id.clear_history_button);
            });
        }
    }

    @Test
    public void largeTextFailureKeepsItsExplanationAndRetryScrollableWithoutAnExtraHostStop() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.fontScale = 2f;
            configuration.screenWidthDp = 288;
            configuration.screenHeightDp = 360;
            Context largeText = new ContextThemeWrapper(context.createConfigurationContext(configuration),
                    R.style.Theme_AllergyCheck);
            ViewGroup page = (ViewGroup) LayoutInflater.from(largeText)
                    .inflate(R.layout.fragment_history, null);
            TextView title = page.findViewById(R.id.history_load_title);
            title.setText(R.string.history_load_failed_title);
            page.findViewById(R.id.history_loading_progress).setVisibility(View.GONE);
            TextView message = page.findViewById(R.id.history_load_message);
            TextView retry = page.findViewById(R.id.history_load_retry);
            message.setVisibility(View.VISIBLE);
            retry.setVisibility(View.VISIBLE);
            float density = largeText.getResources().getDisplayMetrics().density;
            int width = Math.round(288 * density);
            int height = Math.round(360 * density);
            page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            page.layout(0, 0, width, height);

            NestedScrollView panel = page.findViewById(R.id.history_load_state);
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, panel.getImportantForAccessibility());
            assertTrue("Suppressing the empty host must retain a usable scroll viewport",
                    panel.getHeight() >= Math.round(48 * density));
            assertTrue("The enlarged failure explanation must remain scrollable",
                    panel.canScrollVertically(1));
            panel.setSmoothScrollingEnabled(false);
            assertTrue(panel.fullScroll(View.FOCUS_DOWN));
            Rect bounds = new Rect();
            retry.getDrawingRect(bounds);
            panel.offsetDescendantRectToMyCoords(retry, bounds);
            assertTrue("Retry can scroll fully into view with enlarged text",
                    bounds.top >= panel.getScrollY() + panel.getPaddingTop()
                            && bounds.bottom <= panel.getScrollY() + panel.getHeight()
                                    - panel.getPaddingBottom());
            for (TextView text : new TextView[] {message, retry}) {
                assertTrue("Every character must be laid out instead of truncated",
                        text.getLayout().getLineEnd(text.getLayout().getLineCount() - 1)
                                == text.getText().length());
                assertTrue(text.getLayout().getHeight() <= text.getHeight()
                        - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
            }
        });
    }

    @Test
    public void failureAwareApiReportsTheReadErrorWhileLegacyCallbackRemainsCompatible() throws Exception {
        seed();
        database.getWritableDatabase().execSQL("ALTER TABLE scans RENAME TO unreadable_scans");
        AtomicBoolean reportedFailure = new AtomicBoolean();
        CountDownLatch failure = new CountDownLatch(1);
        CountDownLatch legacy = new CountDownLatch(1);
        AtomicBoolean legacyWasEmpty = new AtomicBoolean();
        history.loadWithStatus(10, new ScanHistory.LoadCallback() {
            @Override public void onLoaded(@NonNull List<ScanHistoryItem> items) { failure.countDown(); }
            @Override public void onFailure() {
                reportedFailure.set(true);
                failure.countDown();
            }
        });
        history.load(10, items -> {
            legacyWasEmpty.set(items.isEmpty());
            legacy.countDown();
        });
        assertTrue(failure.await(5, TimeUnit.SECONDS));
        assertTrue(legacy.await(5, TimeUnit.SECONDS));
        assertTrue(reportedFailure.get());
        assertTrue(legacyWasEmpty.get());
    }

    private void seed() {
        store.insertFailure(System.currentTimeMillis(), "Ingredients: milk.", "Saved fixture scan", "Details");
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

        @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
            super.onViewCreated(view, state);
            try {
                Field field = HistoryFragment.class.getDeclaredField("history");
                field.setAccessible(true);
                field.set(this, isolatedHistory);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private void awaitFailure(ActivityScenario<SettingsActivity> screen) {
        await(screen, activity -> activity.findViewById(R.id.history_load_retry).isShown());
        screen.onActivity(activity -> {
            TextView title = activity.findViewById(R.id.history_load_title);
            assertEquals(context.getString(R.string.history_load_failed_title), title.getText().toString());
            assertTrue(activity.findViewById(R.id.history_load_message).isShown());
            assertHidden(activity, R.id.history_empty, R.id.history_list, R.id.clear_history_button);
            assertStatusReadingTargets(activity.findViewById(R.id.history_page), true);
        });
    }

    private static void assertStatusReadingTargets(ViewGroup page, boolean failed) {
        ArrayList<View> targets = new ArrayList<>();
        // Exercise the attached platform tree projection, rather than inspecting XML flags only.
        page.addChildrenForAccessibility(targets);
        assertTrue(targets.contains(page.findViewById(R.id.history_load_title)));
        assertFalse("The empty loading scroll host must not become an Unlabelled reading stop",
                targets.contains(page.findViewById(R.id.history_load_state)));
        assertFalse(targets.contains(page.findViewById(R.id.history_loading_progress)));
        if (failed) {
            assertTrue("Ignoring the host must keep its named error explanation readable",
                    targets.contains(page.findViewById(R.id.history_load_message)));
            assertTrue("Ignoring the host must keep Retry independently available",
                    targets.contains(page.findViewById(R.id.history_load_retry)));
        }
    }

    private static int rows(SettingsActivity activity) {
        return ((RecyclerView) activity.findViewById(R.id.history_list)).getAdapter().getItemCount();
    }

    private static void assertHidden(SettingsActivity activity, int... ids) {
        for (int id : ids) assertEquals(View.GONE, activity.findViewById(id).getVisibility());
    }

    private static void await(ActivityScenario<SettingsActivity> screen, Predicate<SettingsActivity> ready) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            AtomicBoolean result = new AtomicBoolean();
            screen.onActivity(activity -> result.set(ready.test(activity)));
            if (result.get()) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("History did not reach the expected loading/result state");
    }
}
