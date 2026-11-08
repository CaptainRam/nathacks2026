package com.example.allergycheck.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Real ML Kit and Android bitmaps, with controlled preparers for I-04/O-03/O-05–O-08. */
@RunWith(AndroidJUnit4.class)
public class OcrPipelineContractTest {
    @Test
    public void onePixelPreparationPreservesTheCallerAndProducesGrayscale() {
        Bitmap source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        Bitmap prepared = null;
        try {
            prepared = new LabelImagePreparer().prepare(source);
            assertFalse(source.isRecycled());
            assertEquals(Color.RED, source.getPixel(0, 0));
            assertEquals(1, prepared.getWidth());
            assertEquals(1, prepared.getHeight());
            int pixel = prepared.getPixel(0, 0);
            assertEquals(Color.red(pixel), Color.green(pixel));
            assertEquals(Color.red(pixel), Color.blue(pixel));
            assertEquals(255, Color.alpha(pixel));
        } finally {
            if (prepared != null && prepared != source) prepared.recycle();
            source.recycle();
        }
    }

    @Test
    public void emptyPreparedReadRetriesTheOriginalWithoutRecyclingTheCaller() throws Exception {
        Bitmap original = label();
        AtomicReference<Bitmap> prepared = new AtomicReference<>();
        AtomicInteger passes = new AtomicInteger();
        OcrPipeline pipeline = new OcrPipeline(input -> {
            passes.incrementAndGet();
            return input;
        }, input -> {
            Bitmap blank = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
            blank.eraseColor(Color.WHITE);
            prepared.set(blank);
            return blank;
        });
        Outcome outcome = new Outcome();
        try {
            onMain(() -> pipeline.recognize(original, outcome));
            outcome.await();
            assertNull(outcome.error.get());
            assertNotNull(outcome.result.get());
            assertTrue(outcome.result.get().fullText().toLowerCase(java.util.Locale.ROOT).contains("milk"));
            assertEquals("empty prepared pass and one raw retry", 2, passes.get());
            assertEquals(1, outcome.completed.get());
            assertTrue(outcome.onMainThread);
            assertFalse(original.isRecycled());
            assertTrue(prepared.get().isRecycled());
        } finally {
            onMain(pipeline::close);
            original.recycle();
        }
    }

    @Test
    public void missingAndNonImageUrisFailInsteadOfReturningEmptyText() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File nonImage = File.createTempFile("ocr_non_image_", ".txt", context.getCacheDir());
        File missing = new File(context.getCacheDir(), "ocr_missing_" + System.nanoTime() + ".jpg");
        try {
            try (FileOutputStream output = new FileOutputStream(nonImage)) {
                output.write("Ingredients: milk, sugar, salt.".getBytes(StandardCharsets.UTF_8));
            }
            for (File file : new File[] {missing, nonImage}) {
                OcrPipeline pipeline = new OcrPipeline();
                Outcome outcome = new Outcome();
                try {
                    onMain(() -> pipeline.recognize(context, Uri.fromFile(file), outcome));
                    outcome.await();
                    assertNotNull(outcome.error.get());
                    assertNull(outcome.result.get());
                    assertEquals(1, outcome.completed.get());
                    assertTrue(outcome.onMainThread);
                } finally {
                    onMain(pipeline::close);
                }
            }
        } finally {
            assertTrue(nonImage.delete());
        }
    }

    @Test
    public void preparationOutOfMemoryReportsFailureAndPreservesCallerBitmap() throws Exception {
        Bitmap original = label();
        OcrPipeline pipeline = new OcrPipeline(new PassThroughFilter(), input -> {
            throw new OutOfMemoryError("injected preparation allocation failure");
        });
        Outcome outcome = new Outcome();
        try {
            onMain(() -> pipeline.recognize(original, outcome));
            outcome.await();
            assertTrue(outcome.error.get() instanceof IOException);
            assertTrue(outcome.error.get().getMessage().contains("Image too large"));
            assertTrue(outcome.error.get().getCause() instanceof OutOfMemoryError);
            assertNull(outcome.result.get());
            assertEquals(1, outcome.completed.get());
            assertTrue(outcome.onMainThread);
            assertFalse(original.isRecycled());
        } finally {
            onMain(pipeline::close);
            original.recycle();
        }
    }

    @Test
    public void recognizingAfterCloseDoesNoWorkAndDoesNotCallBack() throws Exception {
        Bitmap original = label();
        AtomicInteger preparations = new AtomicInteger();
        OcrPipeline pipeline = new OcrPipeline(new PassThroughFilter(), input -> {
            preparations.incrementAndGet();
            return input;
        });
        Outcome outcome = new Outcome();
        try {
            onMain(() -> {
                outcome.closed = true;
                pipeline.close();
                pipeline.close();
                pipeline.recognize(original, outcome);
                pipeline.recognize(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        Uri.parse("file:///does-not-exist.jpg"), outcome);
            });
            assertFalse(outcome.done.await(300, TimeUnit.MILLISECONDS));
            assertEquals(0, preparations.get());
            assertEquals(0, outcome.completed.get());
            assertEquals(0, outcome.stagesAfterClose.get());
            assertFalse(original.isRecycled());
        } finally {
            original.recycle();
        }
    }

    @Test
    public void closingDuringSuccessfulPreparationSuppressesResultsAndFreesPreparedBitmap() throws Exception {
        closeDuringPreparation(false);
    }

    @Test
    public void closingDuringFailedPreparationAlsoSuppressesTheFailureCallback() throws Exception {
        closeDuringPreparation(true);
    }

    @Test
    public void closingDuringPreparationOutOfMemorySuppressesTheFailureCallback() throws Exception {
        closeDuringPreparation(true, false, true);
    }

    @Test
    public void closingDuringUriPreparationFailureRecyclesTheDecodedPhotoWithoutCallback() throws Exception {
        closeDuringPreparation(true, true, false);
    }

    @Test
    public void closingDuringUriPreparationOutOfMemoryAlsoFreesTheDecodedPhoto() throws Exception {
        closeDuringPreparation(true, true, true);
    }

    private void closeDuringPreparation(boolean fail) throws Exception {
        closeDuringPreparation(fail, false, false);
    }

    private void closeDuringPreparation(boolean fail, boolean useUri, boolean outOfMemory) throws Exception {
        Bitmap original = label();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File photo = useUri ? File.createTempFile("ocr_close_", ".jpg", context.getCacheDir()) : null;
        if (photo != null) {
            try (FileOutputStream output = new FileOutputStream(photo)) {
                assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 100, output));
            }
        }
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Bitmap> prepared = new AtomicReference<>();
        AtomicReference<Bitmap> decodedInput = new AtomicReference<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        OcrPipeline pipeline = new OcrPipeline(new PassThroughFilter(), input -> {
            worker.set(Thread.currentThread());
            decodedInput.set(input);
            // Retain the decoded input only to verify the pipeline frees images that it owns.
            // Bitmap-path inputs remain the caller's even when preparation fails.
            return prepareWhileClosing(input, preparing, release, finished, prepared, outOfMemory, fail);
        });
        Outcome outcome = new Outcome();
        try {
            onMain(() -> {
                if (photo == null) pipeline.recognize(original, outcome);
                else pipeline.recognize(context, Uri.fromFile(photo), outcome);
            });
            assertTrue(preparing.await(5, TimeUnit.SECONDS));
            onMain(() -> {
                outcome.closed = true;
                pipeline.close();
            });
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            worker.get().join(5000);
            assertFalse("closed image worker did not stop", worker.get().isAlive());
            // A main-thread barrier after worker termination drains any queued failure delivery.
            onMain(() -> { });
            assertEquals(0, outcome.completed.get());
            assertEquals(0, outcome.stagesAfterClose.get());
            assertFalse(original.isRecycled());
            if (useUri) {
                assertNotNull(decodedInput.get());
                assertTrue("pipeline-owned decoded image must be freed", decodedInput.get().isRecycled());
            }
            if (!fail) {
                assertNotNull(prepared.get());
                assertTrue(prepared.get().isRecycled());
            }
        } finally {
            release.countDown();
            onMain(pipeline::close);
            if (worker.get() != null) worker.get().join(5000);
            original.recycle();
            if (photo != null) assertTrue(photo.delete());
        }
    }

    private static Bitmap prepareWhileClosing(Bitmap input, CountDownLatch preparing,
            CountDownLatch release, CountDownLatch finished, AtomicReference<Bitmap> prepared,
            boolean outOfMemory, boolean fail) {
        preparing.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("preparer never released");
            if (outOfMemory) throw new OutOfMemoryError("injected preparation allocation failure");
            if (fail) throw new IllegalStateException("injected preparation failure");
            Bitmap copy = input.copy(Bitmap.Config.ARGB_8888, false);
            prepared.set(copy);
            return copy;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        } finally {
            finished.countDown();
        }
    }

    private static Bitmap label() {
        Bitmap bitmap = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(58);
        canvas.drawText("INGREDIENTS: MILK, SUGAR, SALT.", 30, 130, paint);
        return bitmap;
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static class Outcome implements OcrPipeline.Callback {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<OcrResult> result = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger stagesAfterClose = new AtomicInteger();
        volatile boolean closed;
        volatile boolean onMainThread;

        @Override public void onStage(OcrPipeline.Stage stage) {
            if (closed) stagesAfterClose.incrementAndGet();
        }

        @Override public void onSuccess(OcrResult value) {
            result.set(value);
            complete();
        }

        @Override public void onFailure(Exception value) {
            error.set(value);
            complete();
        }

        private void complete() {
            onMainThread = Looper.myLooper() == Looper.getMainLooper();
            completed.incrementAndGet();
            done.countDown();
        }

        void await() throws InterruptedException {
            // The first ML Kit call can initialize native OCR on a freshly booted emulator.
            assertTrue("OCR did not finish", done.await(60, TimeUnit.SECONDS));
        }
    }
}
