package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** A real OCR completion may precede the duplicate enhanced-preview preparation. */
@RunWith(AndroidJUnit4.class)
public class OcrPreviewRaceTest {
    private Context context;
    private SharedPreferences modelPreferences;
    private boolean hadIntroductionPreference;
    private boolean introductionPreference;
    private final List<File> photos = new ArrayList<>();
    private final CountDownLatch releasePreview = new CountDownLatch(1);

    @Before
    public void suppressOnlyTheModelIntroductionDialog() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        modelPreferences = context.getSharedPreferences("local_model", Context.MODE_PRIVATE);
        hadIntroductionPreference = modelPreferences.contains("intro_shown");
        introductionPreference = modelPreferences.getBoolean("intro_shown", false);
        assertTrue(modelPreferences.edit().putBoolean("intro_shown", true).commit());
    }

    @After
    public void cleanUp() {
        releasePreview.countDown();
        for (File photo : photos) photo.delete();
        SharedPreferences.Editor editor = modelPreferences.edit();
        if (hadIntroductionPreference) editor.putBoolean("intro_shown", introductionPreference);
        else editor.remove("intro_shown");
        assertTrue(editor.commit());
    }

    @Test
    public void lateEnhancedPreviewCannotReplaceTheNewerUnenhancedPhoto() throws Exception {
        Uri first = photo(Color.WHITE);
        Uri second = photo(Color.GREEN);
        try (ActivityScenario<OcrTestActivity> screen = ActivityScenario.launch(
                new Intent(context, OcrTestActivity.class))) {
            ExecutorService[] executors = new ExecutorService[1];
            CountDownLatch blocked = new CountDownLatch(1);
            screen.onActivity(activity -> {
                executors[0] = previewExecutor(activity);
                executors[0].execute(() -> {
                    blocked.countDown();
                    try {
                        releasePreview.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            screen.onActivity(activity -> {
                MaterialSwitch enhance = activity.findViewById(R.id.enhance_switch);
                assertTrue(enhance.isChecked());
                runOcr(activity, first);
            });

            // Recognition uses a different executor. Let its real empty-image callback enable
            // the switch while A's enhanced preview remains queued behind the gate.
            awaitEmptyOcr(screen);
            screen.onActivity(activity -> {
                MaterialSwitch enhance = activity.findViewById(R.id.enhance_switch);
                assertTrue(enhance.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertEquals(false, enhance.isChecked());
                runOcr(activity, second);
                assertGreenPreview(activity);
            });

            releasePreview.countDown();
            // A posts its completed preview before this trailing queue barrier completes.
            // Waiting for the main queue afterwards ensures that stale callback has run.
            executors[0].submit(() -> { }).get(10, TimeUnit.SECONDS);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(OcrPreviewRaceTest::assertGreenPreview);
        } finally {
            releasePreview.countDown();
        }
    }

    private Uri photo(int color) throws Exception {
        File directory = new File(context.getFilesDir(), "photos");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = File.createTempFile("capture_ocr_preview_race_", ".png", directory);
        photos.add(file);
        Bitmap bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
    }

    private static void assertGreenPreview(OcrTestActivity activity) {
        ImageView preview = activity.findViewById(R.id.image_preview);
        assertNotNull(preview.getDrawable());
        assertTrue(preview.getDrawable() instanceof BitmapDrawable);
        Bitmap bitmap = ((BitmapDrawable) preview.getDrawable()).getBitmap();
        Bitmap readable = bitmap.getConfig() == Bitmap.Config.HARDWARE
                ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : bitmap;
        assertNotNull(readable);
        try {
            int color = readable.getPixel(readable.getWidth() / 2, readable.getHeight() / 2);
            assertTrue("The preview must remain photo B (green), not late photo A: "
                            + Integer.toHexString(color),
                    Color.green(color) > 200 && Color.red(color) < 50 && Color.blue(color) < 50);
        } finally {
            if (readable != bitmap) readable.recycle();
        }
    }

    private void awaitEmptyOcr(ActivityScenario<OcrTestActivity> screen) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        do {
            boolean[] ready = {false};
            screen.onActivity(activity -> ready[0] = activity.findViewById(R.id.enhance_switch).isEnabled()
                    && context.getString(R.string.ocr_output_empty).contentEquals(
                            ((TextView) activity.findViewById(R.id.ocr_status)).getText()));
            if (ready[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Photo A did not complete real empty-image OCR");
    }

    private static ExecutorService previewExecutor(OcrTestActivity activity) {
        try {
            Field field = OcrTestActivity.class.getDeclaredField("previewExecutor");
            field.setAccessible(true);
            return (ExecutorService) field.get(activity);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void runOcr(OcrTestActivity activity, Uri uri) {
        try {
            Method method = OcrTestActivity.class.getDeclaredMethod("runOcr", Uri.class);
            method.setAccessible(true);
            method.invoke(activity, uri);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
