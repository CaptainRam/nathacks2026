package com.example.allergycheck.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** Writing the display copy of a label photo. */
@RunWith(AndroidJUnit4.class)
public class ScanHistoryImagesWriteTest {

    private Context context;
    private File directory;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        directory = new File(context.getCacheDir(), "history_write_" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
    }

    @After
    public void tearDown() {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        directory.delete();
    }

    @Test
    public void aLargeLandscapePhotoIsBoundedAndKeepsItsAspectRatio() throws IOException {
        Uri source = writeSourceJpeg(3000, 2000);
        File target = new File(directory, ScanHistoryImages.fileNameFor(1));

        ScanHistoryImages.write(context, source, target);

        BitmapFactory.Options bounds = decodeBounds(target);
        assertEquals(ScanHistoryImages.LONG_EDGE_PX, bounds.outWidth);
        assertCloseTo(ScanHistoryImages.LONG_EDGE_PX * 2 / 3, bounds.outHeight, 2);
        assertTrue("should actually have written bytes", target.length() > 0);
    }

    @Test
    public void aLargePortraitPhotoIsBoundedOnItsLongEdge() throws IOException {
        Uri source = writeSourceJpeg(1500, 3000);
        File target = new File(directory, ScanHistoryImages.fileNameFor(2));

        ScanHistoryImages.write(context, source, target);

        BitmapFactory.Options bounds = decodeBounds(target);
        assertEquals(ScanHistoryImages.LONG_EDGE_PX, bounds.outHeight);
        assertCloseTo(ScanHistoryImages.LONG_EDGE_PX / 2, bounds.outWidth, 2);
    }

    @Test
    public void aPhotoSmallerThanTheBoundIsNotUpscaled() throws IOException {
        // Unlike the OCR decoder, which upscales tight crops on purpose, there is nothing to gain
        // from inventing pixels for something that is only ever drawn.
        Uri source = writeSourceJpeg(640, 480);
        File target = new File(directory, ScanHistoryImages.fileNameFor(3));

        ScanHistoryImages.write(context, source, target);

        BitmapFactory.Options bounds = decodeBounds(target);
        assertEquals(640, bounds.outWidth);
        assertEquals(480, bounds.outHeight);
    }

    @Test
    public void theResultIsReadableAsAJpeg() throws IOException {
        Uri source = writeSourceJpeg(2000, 1200);
        File target = new File(directory, ScanHistoryImages.fileNameFor(4));

        ScanHistoryImages.write(context, source, target);

        Bitmap decoded = BitmapFactory.decodeFile(target.getAbsolutePath());
        assertNotNull("the saved file should decode", decoded);
        decoded.recycle();
    }

    @Test
    public void aMissingDirectoryIsCreated() throws IOException {
        Uri source = writeSourceJpeg(800, 600);
        File nested = new File(directory, "created/" + ScanHistoryImages.fileNameFor(5));

        ScanHistoryImages.write(context, source, nested);

        assertTrue(nested.exists());
    }

    @Test
    public void aSourceThatIsNotAnImageFailsAndLeavesNoFile() throws IOException {
        File junk = new File(directory, "not_an_image.bin");
        try (OutputStream out = new FileOutputStream(junk)) {
            out.write("this is not a photo".getBytes());
        }
        File target = new File(directory, ScanHistoryImages.fileNameFor(6));

        try {
            ScanHistoryImages.write(context, Uri.fromFile(junk), target);
            fail("expected the decode to fail");
        } catch (IOException | RuntimeException expected) {
            // The point is the file system, not the exception type.
        }
        assertFalse("a failed write must not leave a partial image", target.exists());
    }

    @Test
    public void deleteRemovesASavedImage() throws IOException {
        Uri source = writeSourceJpeg(800, 600);
        String name = ScanHistoryImages.fileNameFor(7);
        File target = new File(directory, name);
        ScanHistoryImages.write(context, source, target);
        assertTrue(target.exists());

        ScanHistoryImages.delete(directory, name);

        assertFalse(target.exists());
    }

    @Test
    public void deleteIgnoresAMissingFileAndANullName() {
        ScanHistoryImages.delete(directory, ScanHistoryImages.fileNameFor(999));
        ScanHistoryImages.delete(directory, null);
        ScanHistoryImages.delete(directory, "");
    }

    @Test
    public void deleteRefusesToReachOutsideTheHistoryDirectory() throws IOException {
        File outside = new File(context.getCacheDir(), "outside_" + UUID.randomUUID() + ".jpg");
        try (OutputStream out = new FileOutputStream(outside)) {
            out.write(new byte[]{1, 2, 3});
        }
        assertTrue(outside.exists());

        ScanHistoryImages.delete(directory, "../" + outside.getName());

        assertTrue("a traversing name must not delete anything", outside.exists());
        outside.delete();
    }

    // ----- Helpers -----

    /** A synthetic label-ish photo on disk, addressed by a file Uri. */
    private Uri writeSourceJpeg(int width, int height) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(height / 10f);
        canvas.drawText("INGREDIENTS", width / 20f, height / 2f, paint);
        File file = new File(directory, "source_" + width + "x" + height + ".jpg");
        try (OutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
        } finally {
            bitmap.recycle();
        }
        return Uri.fromFile(file);
    }

    private static BitmapFactory.Options decodeBounds(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return options;
    }

    private static void assertCloseTo(int expected, int actual, int tolerance) {
        assertTrue("expected " + expected + " +/- " + tolerance + " but was " + actual,
                Math.abs(expected - actual) <= tolerance);
    }
}
