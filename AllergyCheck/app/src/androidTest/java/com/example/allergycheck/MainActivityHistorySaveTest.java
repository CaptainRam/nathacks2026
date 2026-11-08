package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.history.ScanHistoryDatabase;
import com.example.allergycheck.history.ScanHistoryDetail;
import com.example.allergycheck.history.ScanHistoryImages;
import com.example.allergycheck.history.ScanHistoryStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * That a scan the user can see is a scan that gets recorded, and that one they cannot is not.
 *
 * <p>Reads the app's real history database rather than a test one, because the point is the wiring
 * in {@link MainActivity}. Rows created here are deleted again in {@code tearDown}, and existing
 * rows are identified up front and left alone.
 */
@RunWith(AndroidJUnit4.class)
public class MainActivityHistorySaveTest {

    private Context context;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private File imageDirectory;
    private Set<Long> preexisting;
    private ScanTestSupport support;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        database = new ScanHistoryDatabase(context);
        imageDirectory = new File(context.getFilesDir(), "history");
        store = new ScanHistoryStore(database, imageDirectory);
        preexisting = new HashSet<>(store.liveIds());
        support = new ScanTestSupport(context);
    }

    @After
    public void tearDown() {
        for (long id : newRowIds()) {
            store.delete(id);
            ScanHistoryImages.delete(imageDirectory, ScanHistoryImages.fileNameFor(id));
        }
        database.close();
        support.close();
    }

    @Test
    public void aFinishedScanIsRecordedWithItsVerdictAndItsPhoto() throws Exception {
        File photo = support.createLabel("INGREDIENTS:", "Sugar, Milk Powder, Salt.");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            support.loadPhoto(scan, photo);
            ScanTestSupport.startCheck(scan);
            awaitResult(scan);

            long id = awaitExactlyOneNewRow();
            ScanHistoryDetail detail = store.detail(id);
            assertNotNull(detail);
            assertNotNull("a finished scan must store a report", detail.report());
            assertFalse(detail.item().isFailure());
            assertTrue("the OCR text should have been kept",
                    detail.rawText().toUpperCase().contains("SUGAR"));

            // The image is attached after the row, so give the worker a moment to finish.
            String imageName = awaitImageAttached(id);
            assertNotNull("the photo should have been saved", imageName);
            File saved = new File(imageDirectory, imageName);
            assertTrue("the saved image should exist on disk", saved.exists());
            assertTrue(saved.length() > 0);
        }
    }

    @Test
    public void theStoredVerdictMatchesWhatWasShown() throws Exception {
        File photo = support.createLabel("INGREDIENTS:", "Sugar, Milk Powder, Salt.");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            support.loadPhoto(scan, photo);
            ScanTestSupport.startCheck(scan);
            awaitResult(scan);

            long id = awaitExactlyOneNewRow();
            ScanHistoryDetail detail = store.detail(id);
            assertNotNull(detail);
            // Whatever the verdict, the denormalised column and the report must agree - that is
            // what lets the list draw a row without reading the blob.
            assertEquals(detail.report().overall(), detail.item().status());
        }
    }

    @Test
    public void aBlankPhotoIsRecordedAsAFailureWithNoReport() throws Exception {
        File photo = support.createBlankPhoto();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            support.loadPhoto(scan, photo);
            ScanTestSupport.startCheck(scan);
            awaitResult(scan);

            long id = awaitExactlyOneNewRow();
            ScanHistoryDetail detail = store.detail(id);
            assertNotNull(detail);
            assertNull("there was no report to store", detail.report());
            assertTrue(detail.item().isFailure());
            assertNotNull("the message that was shown must be kept",
                    detail.item().messageTitle());
            assertNotNull(detail.messageBody());
            // Never a status that reads as clear.
            assertEquals(AnalysisReport.Status.UNREADABLE, detail.item().status());
        }
    }

    @Test
    public void aScanCancelledBeforeItFinishesIsNotRecorded() throws Exception {
        File photo = support.createLabel("INGREDIENTS:", "Sugar, Milk Powder, Salt.");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            support.loadPhoto(scan, photo);
            ScanTestSupport.startCheck(scan);
            // Cancel immediately: the staleness guard in recognizeIngredients should drop the
            // result before it reaches either the screen or history.
            scan.onActivity(activity ->
                    assertTrue(activity.findViewById(R.id.cancel_analysis_button).performClick()));

            // Give the recogniser long enough to have come back had it been going to.
            SystemClock.sleep(6000);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(500);

            assertTrue("a cancelled scan must not be recorded: " + newRowIds(),
                    newRowIds().isEmpty());
        }
    }

    @Test
    public void justOpeningTheAppRecordsNothing() throws Exception {
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            scan.onActivity(activity -> assertNotNull(activity));
            SystemClock.sleep(1500);
            assertTrue(newRowIds().isEmpty());
        }
    }

    // ----- Helpers -----

    private Set<Long> newRowIds() {
        Set<Long> ids = new HashSet<>(store.liveIds());
        ids.removeAll(preexisting);
        return ids;
    }

    /** The save is handed to a worker, so the row appears a moment after the verdict does. */
    private long awaitExactlyOneNewRow() {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            Set<Long> ids = newRowIds();
            if (ids.size() == 1) {
                return ids.iterator().next();
            }
            assertTrue("more than one row was recorded: " + ids, ids.size() <= 1);
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The scan was not recorded");
    }

    private String awaitImageAttached(long id) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            ScanHistoryDetail detail = store.detail(id);
            if (detail != null && detail.item().imageName() != null) {
                return detail.item().imageName();
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    /** Waits until the in-page result is on screen, however it turned out. */
    private static void awaitResult(ScanTestSupport.Scan scan) {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        do {
            boolean[] shown = {false};
            scan.onActivity(activity -> {
                View result = activity.findViewById(R.id.analysis_result);
                shown[0] = result != null && result.isShown();
            });
            if (shown[0]) {
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No result appeared");
    }
}
