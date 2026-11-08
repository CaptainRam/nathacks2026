package com.example.allergycheck.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Housekeeping: that {@code maintain()} prunes the retention window and sweeps orphaned images in
 * one pass, and that it leaves everything else alone.
 */
@RunWith(AndroidJUnit4.class)
public class ScanHistoryMaintainTest {

    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private Context context;
    private String fileName;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private ScanHistory history;
    private File imageDirectory;
    private AllergenDictionary dictionary;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();
        fileName = "test_maintain_" + UUID.randomUUID() + ".db";
        database = new ScanHistoryDatabase(context, fileName);
        imageDirectory = new File(context.getCacheDir(), "maintain_" + UUID.randomUUID());
        assertTrue(imageDirectory.mkdirs());
        store = new ScanHistoryStore(database, imageDirectory);
        history = new ScanHistory(context, store, imageDirectory);
        dictionary = shippedDictionary();
    }

    @After
    public void tearDown() {
        database.close();
        context.deleteDatabase(fileName);
        File[] files = imageDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        imageDirectory.delete();
    }

    @Test
    public void anExpiredRowAndItsImageAreBothRemoved() throws Exception {
        long now = System.currentTimeMillis();
        long expired = store.insert(now - 8 * DAY, analyse("Ingredients: milk."));
        store.attachImage(expired, ScanHistoryImages.fileNameFor(expired));
        File expiredImage = touch(ScanHistoryImages.fileNameFor(expired));

        long fresh = store.insert(now, analyse("Ingredients: soy."));
        store.attachImage(fresh, ScanHistoryImages.fileNameFor(fresh));
        File freshImage = touch(ScanHistoryImages.fileNameFor(fresh));

        await();

        assertEquals(1, store.count());
        assertFalse("the expired row should be gone", store.liveIds().contains(expired));
        assertTrue("the fresh row should remain", store.liveIds().contains(fresh));
        assertFalse("the expired row's image should be gone", expiredImage.exists());
        assertTrue("the fresh row's image should remain", freshImage.exists());
    }

    @Test
    public void anImageWithNoRowIsSweptInTheSamePass() throws Exception {
        long live = store.insert(System.currentTimeMillis(), analyse("Ingredients: milk."));
        store.attachImage(live, ScanHistoryImages.fileNameFor(live));
        File liveImage = touch(ScanHistoryImages.fileNameFor(live));
        File orphan = touch(ScanHistoryImages.fileNameFor(999));

        await();

        assertFalse("an image with no row should be swept", orphan.exists());
        assertTrue("a live row's image must survive", liveImage.exists());
    }

    @Test
    public void filesThatAreNotOursAreLeftAlone() throws Exception {
        File unrelated = touch("notes.txt");
        File staging = touch("staging_1.jpg");
        File hidden = touch(".nomedia");

        await();

        assertTrue(unrelated.exists());
        assertTrue(staging.exists());
        assertTrue(hidden.exists());
    }

    @Test
    public void anImageStrandedByACrashedSaveIsStillCleanedUp() throws Exception {
        // A save that wrote the JPEG but died before attachImage leaves image_name null.
        long expired = store.insert(System.currentTimeMillis() - 8 * DAY, analyse("Ingredients: milk."));
        File stranded = touch(ScanHistoryImages.fileNameFor(expired));

        await();

        assertFalse(stranded.exists());
        assertEquals(0, store.count());
    }

    @Test
    public void maintainingEmptyHistoryIsHarmless() throws Exception {
        await();
        assertEquals(0, store.count());
    }

    @Test
    public void deleteRemovesTheImageEvenWhenTheRowNeverAttachedOne() throws Exception {
        long id = store.insert(System.currentTimeMillis(), analyse("Ingredients: milk."));
        File image = touch(ScanHistoryImages.fileNameFor(id));

        CountDownLatch done = new CountDownLatch(1);
        runOnMain(() -> history.delete(id, done::countDown));
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(0, store.count());
        assertFalse(image.exists());
    }

    @Test
    public void clearRemovesEveryRowAndEveryImageOfOurs() throws Exception {
        long now = System.currentTimeMillis();
        long first = store.insert(now, analyse("Ingredients: milk."));
        long second = store.insert(now, analyse("Ingredients: soy."));
        store.attachImage(first, ScanHistoryImages.fileNameFor(first));
        File firstImage = touch(ScanHistoryImages.fileNameFor(first));
        File secondImage = touch(ScanHistoryImages.fileNameFor(second));
        File unrelated = touch("notes.txt");

        CountDownLatch done = new CountDownLatch(1);
        runOnMain(() -> history.clear(done::countDown));
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(0, store.count());
        assertFalse(firstImage.exists());
        assertFalse(secondImage.exists());
        assertTrue("clear must not touch files that are not ours", unrelated.exists());
    }

    // ----- Helpers -----

    private void await() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        runOnMain(() -> history.maintain(done::countDown));
        assertTrue("maintain did not finish", done.await(10, TimeUnit.SECONDS));
    }

    /** {@link ScanHistory} is main-thread-only by contract, so entry points are posted there. */
    private static void runOnMain(Runnable action) {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(action);
    }

    private File touch(String name) throws IOException {
        File file = new File(imageDirectory, name);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3});
        }
        assertTrue(file.exists());
        return file;
    }

    private AnalysisReport analyse(String text) {
        return Analyzer.analyse(text, dictionary, dictionary.allergens());
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
