package com.example.allergycheck;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The history pages include the same result layout as the scan page, so once they are attached the
 * result ids exist twice in one hierarchy. That is legal — {@code AnalysisResultView} resolves
 * every id through the root it is handed, and a fragment scopes lookups to its own view — but
 * {@code Activity#findViewById} is a depth-first search, and the existing scan tests use it.
 *
 * <p>This pins the arrangement that keeps those tests honest: the scan content comes first in
 * {@code activity_main.xml}, so an activity-level lookup always finds the scan page's copy.
 */
@RunWith(AndroidJUnit4.class)
public class HistoryIdIsolationTest {

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void theScanPageKeepsOwningTheResultIdsAcrossTabSwitches() {
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            AtomicReference<View> atStartup = new AtomicReference<>();
            scan.onActivity(activity -> {
                View result = activity.findViewById(R.id.analysis_result);
                assertNotNull("the scan page's result should be findable", result);
                atStartup.set(result);
            });

            // Before any tab is tapped there is no history fragment at all, so nothing is
            // duplicated yet. This is what the pre-existing scan tests rely on.
            scan.onActivity(activity -> assertNoHistoryFragment(
                    activity.getSupportFragmentManager().findFragmentById(R.id.history_container)));

            selectTab(scan, R.id.tab_history);
            awaitHistoryShown(scan);
            scan.onActivity(activity -> assertSame(
                    "history must not take over the result ids",
                    atStartup.get(), activity.findViewById(R.id.analysis_result)));

            selectTab(scan, R.id.tab_scan);
            scan.onActivity(activity -> assertSame(
                    "returning to scan must find the same view",
                    atStartup.get(), activity.findViewById(R.id.analysis_result)));
        }
    }

    @Test
    public void theScanPageComesBackAfterVisitingHistory() {
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            selectTab(scan, R.id.tab_history);
            awaitHistoryShown(scan);
            scan.onActivity(activity ->
                    assertTrue(activity.findViewById(R.id.history_container).isShown()));

            selectTab(scan, R.id.tab_scan);
            scan.onActivity(activity -> {
                assertTrue("the scan page should be back",
                        activity.findViewById(R.id.content_scroll).isShown());
                assertTrue("the photo card should be back",
                        activity.findViewById(R.id.photo_card).isShown());
            });
        }
    }

    @Test
    public void backFromHistoryReturnsToScanRatherThanLeaving() {
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            selectTab(scan, R.id.tab_history);
            awaitHistoryShown(scan);

            scan.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scan.onActivity(activity -> {
                assertTrue("back should return to the scan page",
                        activity.findViewById(R.id.content_scroll).isShown());
                assertTrue("the activity should still be running", !activity.isFinishing());
            });
        }
    }

    // ----- Helpers -----

    private static void selectTab(ScanTestSupport.Scan scan, int itemId) {
        scan.onActivity(activity -> {
            com.google.android.material.bottomnavigation.BottomNavigationView nav =
                    activity.findViewById(R.id.bottom_nav);
            nav.setSelectedItemId(itemId);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void awaitHistoryShown(ScanTestSupport.Scan scan) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            boolean[] shown = {false};
            scan.onActivity(activity ->
                    shown[0] = activity.findViewById(R.id.history_container).isShown());
            if (shown[0]) {
                return;
            }
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The history page did not appear");
    }

    private static void assertNoHistoryFragment(Object fragment) {
        org.junit.Assert.assertNull(
                "no history fragment should exist before a tab is tapped", fragment);
    }
}
