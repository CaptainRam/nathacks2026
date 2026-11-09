package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.ingredients.LlmIngredientReader;
import com.example.allergycheck.ingredients.LocalModelManager;
import com.example.allergycheck.ingredients.ModelFiles;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Real OCR completion and interrupted work survive recreation without running a native LLM. */
@RunWith(AndroidJUnit4.class)
public class OcrRotationStateTest {
    private Context context;
    private SharedPreferences modelPreferences;
    private boolean hadIntroduction;
    private boolean introduction;
    private final List<File> photos = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    @Before
    public void suppressTheDownloadIntroduction() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        modelPreferences = context.getSharedPreferences("local_model", Context.MODE_PRIVATE);
        hadIntroduction = modelPreferences.contains("intro_shown");
        introduction = modelPreferences.getBoolean("intro_shown", false);
        assertTrue(modelPreferences.edit().putBoolean("intro_shown", true).commit());
    }

    @After
    public void cleanUp() {
        for (CountDownLatch gate : gates) gate.countDown();
        for (File photo : photos) photo.delete();
        SharedPreferences.Editor editor = modelPreferences.edit();
        if (hadIntroduction) editor.putBoolean("intro_shown", introduction);
        else editor.remove("intro_shown");
        assertTrue(editor.commit());
    }

    @Test
    public void completedEmptyReadRetainsPreviewOutputAndSpokenStatistics() throws Exception {
        Uri image = photo(false);
        try (ActivityScenario<OcrTestActivity> screen = launch()) {
            startWithoutEnhancement(screen, image);
            awaitTerminal(screen, R.string.ocr_output_empty);
            Snapshot[] before = new Snapshot[1];
            screen.onActivity(activity -> {
                assertGreenPreview(activity);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.ocr_stats).getVisibility());
                before[0] = new Snapshot(activity);
            });

            screen.recreate();
            screen.onActivity(activity -> {
                before[0].assertUnchanged(activity);
                assertGreenPreview(activity);
                assertFalse(((MaterialSwitch) activity.findViewById(R.id.enhance_switch)).isChecked());
                assertTrue(activity.findViewById(R.id.enhance_switch).isEnabled());
                assertEquals(View.GONE, activity.findViewById(R.id.progress).getVisibility());
            });
        }
    }

    @Test
    public void unreadableImageRetainsItsActualErrorAfterRecreation() throws Exception {
        Uri image = photo(true);
        try (ActivityScenario<OcrTestActivity> screen = launch()) {
            startWithoutEnhancement(screen, image);
            awaitTerminal(screen, R.string.ocr_output_failed);
            Snapshot[] before = new Snapshot[1];
            screen.onActivity(activity -> {
                assertFalse(((TextView) activity.findViewById(R.id.ocr_output)).getText()
                        .toString().isEmpty());
                before[0] = new Snapshot(activity);
            });
            screen.recreate();
            screen.onActivity(activity -> {
                before[0].assertUnchanged(activity);
                assertEquals(View.GONE, activity.findViewById(R.id.ocr_stats).getVisibility());
                assertTrue(activity.findViewById(R.id.enhance_switch).isEnabled());
            });
        }
    }

    @Test
    public void interruptedOcrResumesAndAnOldQueueCannotReplaceItsRestoredResult() throws Exception {
        Uri image = photo(false);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        gates.add(release);
        ExecutorService[] oldQueue = new ExecutorService[1];
        Future<?>[] drained = new Future<?>[1];
        try (ActivityScenario<OcrTestActivity> screen = launch()) {
            screen.onActivity(activity -> {
                ((MaterialSwitch) activity.findViewById(R.id.enhance_switch)).setChecked(false);
                Object pipeline = field(activity, "ocrPipeline");
                oldQueue[0] = field(pipeline, "imageExecutor");
                oldQueue[0].execute(() -> {
                    blocked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            screen.onActivity(activity -> {
                runOcr(activity, image);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.progress).getVisibility());
                assertFalse(activity.findViewById(R.id.enhance_switch).isEnabled());
                drained[0] = oldQueue[0].submit(() -> { });
            });

            screen.recreate();
            awaitTerminal(screen, R.string.ocr_output_empty);
            Snapshot[] completed = new Snapshot[1];
            screen.onActivity(activity -> {
                assertGreenPreview(activity);
                assertFalse(((MaterialSwitch) activity.findViewById(R.id.enhance_switch)).isChecked());
                completed[0] = new Snapshot(activity);
            });
            release.countDown();
            drained[0].get(10, TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> completed[0].assertUnchanged(activity));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void waitingTextSurvivesAndReadinessSchedulesItOnlyOnce() throws Exception {
        Uri image = photo(false);
        LocalModelManager[] manager = new LocalModelManager[1];
        ModelFiles[] realFiles = new ModelFiles[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            manager[0] = LocalModelManager.get(context);
            assertFalse("This fixture requires no active model transfer",
                    manager[0].getStatus().isBusy());
            realFiles[0] = field(manager[0], "files");
            // Isolate only model-file discovery. No real model file is renamed or deleted.
            setField(manager[0], "files", new ModelFiles(new File(context.getCacheDir(),
                    "ocr_restore_missing_" + System.nanoTime())));
            manager[0].refresh();
        });
        try (ActivityScenario<OcrTestActivity> screen = launch()) {
            startWithoutEnhancement(screen, image);
            awaitTerminal(screen, R.string.ocr_output_empty);
            String pending = "Ingredients: milk powder, sugar.";
            Snapshot[] waiting = new Snapshot[1];
            screen.onActivity(activity -> {
                int request = field(activity, "requestId");
                invoke(activity, "readIngredients", new Class<?>[] {String.class, int.class},
                        pending, request);
                waiting[0] = new Snapshot(activity);
            });

            screen.recreate();
            screen.onActivity(activity -> {
                waiting[0].assertUnchanged(activity);
                assertGreenPreview(activity);
                assertEquals(pending, field(activity, "pendingOcrText"));

                LlmIngredientReader reader = field(activity, "ingredientReader");
                ExecutorService unusedWorker = field(reader, "worker");
                RecordingExecutor queuedModel = new RecordingExecutor();
                setField(reader, "worker", queuedModel);
                unusedWorker.shutdown();
                // Observe scheduling, not inference: the recording queue executes no native code.
                signalInstalled(activity, manager[0]);
                signalInstalled(activity, manager[0]);
                assertEquals("The restored pending text must be consumed once", 1,
                        queuedModel.submitted);
                assertNull(field(activity, "pendingOcrText"));
                assertEquals(context.getString(R.string.reading_ingredients),
                        ((TextView) activity.findViewById(R.id.ocr_status)).getText().toString());
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                setField(manager[0], "files", realFiles[0]);
                manager[0].refresh();
            });
        }
    }

    @Test
    public void rotatingDuringModelWorkKeepsOneReaderAndOneGeneration() throws Exception {
        Uri image = photo(false);
        LocalModelManager[] manager = new LocalModelManager[1];
        LlmIngredientReader[] retainedReader = new LlmIngredientReader[1];
        OcrTestActivity[] destroyedScreen = new OcrTestActivity[1];
        TextView[] destroyedOutput = new TextView[1];
        RecordingExecutor modelQueue = new RecordingExecutor();
        try (ActivityScenario<OcrTestActivity> screen = launch()) {
            startWithoutEnhancement(screen, image);
            awaitTerminal(screen, R.string.ocr_output_empty);
            screen.onActivity(activity -> {
                manager[0] = LocalModelManager.get(context);
                retainedReader[0] = field(activity, "ingredientReader");
                ExecutorService unused = field(retainedReader[0], "worker");
                setField(retainedReader[0], "worker", modelQueue);
                unused.shutdown();
                signalInstalled(activity, manager[0]);
                int request = field(activity, "requestId");
                invoke(activity, "readIngredients", new Class<?>[] {String.class, int.class},
                        "Ingredients: milk, sugar.", request);
                assertEquals(1, modelQueue.submitted);
                destroyedScreen[0] = activity;
                destroyedOutput[0] = activity.findViewById(R.id.ocr_output);
            });

            screen.recreate();
            Snapshot[] completed = new Snapshot[1];
            screen.onActivity(activity -> {
                assertNotSame("Recreation must replace the Activity", destroyedScreen[0], activity);
                assertTrue(destroyedScreen[0].isDestroyed());
                assertNotSame("Verify the old view, not a hierarchy found through a reused Decor",
                        destroyedOutput[0], activity.findViewById(R.id.ocr_output));
                assertSame("A native reader must survive rotation", retainedReader[0],
                        field(activity, "ingredientReader"));
                assertFalse((Boolean) field(retainedReader[0], "closed"));
                assertEquals("Rotation must not enqueue another native generation", 1,
                        modelQueue.submitted);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.progress).getVisibility());
                Object work = field(activity, "modelReads");
                int generation = field(activity, "modelGeneration");
                // Complete the held original task without ever executing native inference.
                invoke(work, "complete", new Class<?>[] {int.class, List.class, Exception.class},
                        generation, Arrays.asList("milk", "sugar"), null);
                assertEquals(context.getString(R.string.ocr_output_ready),
                        ((TextView) activity.findViewById(R.id.ocr_status)).getText().toString());
                assertTrue(((TextView) activity.findViewById(R.id.ocr_output)).getText()
                        .toString().contains("milk"));
                assertEquals("The destroyed screen must not receive the retained answer",
                        context.getString(R.string.reading_ingredients),
                        destroyedOutput[0].getText().toString());
                assertGreenPreview(activity);
                completed[0] = new Snapshot(activity);
            });
            screen.recreate();
            screen.onActivity(activity -> {
                completed[0].assertUnchanged(activity);
                assertSame(retainedReader[0], field(activity, "ingredientReader"));
                assertEquals(1, modelQueue.submitted);
            });
        } finally {
            if (manager[0] != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(manager[0]::refresh);
            }
        }
        assertTrue("Finishing, unlike rotating, releases the reader",
                (Boolean) field(retainedReader[0], "closed"));
    }

    private ActivityScenario<OcrTestActivity> launch() {
        return ActivityScenario.launch(new Intent(context, OcrTestActivity.class));
    }

    private void startWithoutEnhancement(ActivityScenario<OcrTestActivity> screen, Uri image) {
        screen.onActivity(activity -> {
            ((MaterialSwitch) activity.findViewById(R.id.enhance_switch)).setChecked(false);
            runOcr(activity, image);
        });
    }

    private void awaitTerminal(ActivityScenario<OcrTestActivity> screen, int status) {
        long deadline = SystemClock.uptimeMillis() + 20000;
        do {
            boolean[] ready = {false};
            screen.onActivity(activity -> ready[0] = activity.findViewById(R.id.enhance_switch)
                    .isEnabled() && context.getString(status).contentEquals(
                            ((TextView) activity.findViewById(R.id.ocr_status)).getText()));
            if (ready[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("OCR did not reach the expected terminal state");
    }

    private Uri photo(boolean corrupt) throws Exception {
        File directory = new File(context.getFilesDir(), "photos");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = File.createTempFile("capture_ocr_rotation_", ".png", directory);
        photos.add(file);
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (corrupt) {
                output.write(new byte[] {1, 2, 3, 4});
            } else {
                Bitmap bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.GREEN);
                try {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                } finally {
                    bitmap.recycle();
                }
            }
        }
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
    }

    private static void assertGreenPreview(OcrTestActivity activity) {
        ImageView image = activity.findViewById(R.id.image_preview);
        assertEquals(View.VISIBLE, image.getVisibility());
        assertTrue(image.getDrawable() instanceof BitmapDrawable);
        Bitmap original = ((BitmapDrawable) image.getDrawable()).getBitmap();
        Bitmap bitmap = original.getConfig() == Bitmap.Config.HARDWARE
                ? original.copy(Bitmap.Config.ARGB_8888, false) : original;
        assertNotNull(bitmap);
        try {
            int pixel = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
            assertTrue(Color.green(pixel) > 200 && Color.red(pixel) < 50 && Color.blue(pixel) < 50);
        } finally {
            if (bitmap != original) bitmap.recycle();
        }
    }

    private static void runOcr(OcrTestActivity activity, Uri image) {
        invoke(activity, "runOcr", new Class<?>[] {Uri.class}, image);
    }

    private static void signalInstalled(OcrTestActivity activity, LocalModelManager manager) {
        try {
            Constructor<LocalModelManager.Status> constructor = LocalModelManager.Status.class
                    .getDeclaredConstructor(LocalModelManager.State.class, long.class, long.class,
                            String.class);
            constructor.setAccessible(true);
            LocalModelManager.Status ready = constructor.newInstance(
                    LocalModelManager.State.READY, 0L, 0L, null);
            setField(manager, "status", ready);
            LocalModelManager.Listener listener = field(activity, "modelListener");
            listener.onModelStatusChanged(ready);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void invoke(Object target, String name, Class<?>[] parameters, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            method.invoke(target, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class Snapshot {
        private static final int[] IDS = {R.id.ocr_output, R.id.ocr_status, R.id.ocr_stats};
        private final String[] text = new String[IDS.length];
        private final String[] descriptions = new String[IDS.length];
        private final int[] visibility = new int[IDS.length];

        Snapshot(OcrTestActivity activity) {
            for (int index = 0; index < IDS.length; index++) {
                TextView view = activity.findViewById(IDS[index]);
                text[index] = view.getText().toString();
                CharSequence description = view.getContentDescription();
                descriptions[index] = description == null ? null : description.toString();
                visibility[index] = view.getVisibility();
            }
        }

        void assertUnchanged(OcrTestActivity activity) {
            Snapshot actual = new Snapshot(activity);
            for (int index = 0; index < IDS.length; index++) {
                assertEquals(text[index], actual.text[index]);
                assertEquals(descriptions[index], actual.descriptions[index]);
                assertEquals(visibility[index], actual.visibility[index]);
            }
        }
    }

    /** A queue that records model work but never executes it. */
    private static final class RecordingExecutor extends AbstractExecutorService {
        int submitted;
        private boolean stopped;

        @Override public void execute(Runnable task) { submitted++; }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }
}
