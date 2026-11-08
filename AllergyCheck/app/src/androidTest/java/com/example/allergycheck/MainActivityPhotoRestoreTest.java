package com.example.allergycheck;

import android.app.Instrumentation;
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

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class MainActivityPhotoRestoreTest {
    private static final String PHOTO_STATE = "selected_photo";
    private final List<File> photos = new ArrayList<>();
    private Context context;
    private SharedPreferences preferences;
    private String previousPhoto;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = AllergenPreferences.preferences(context);
        previousPhoto = preferences.getString(PHOTO_STATE, null);
    }

    @After
    public void tearDown() {
        SharedPreferences.Editor editor = preferences.edit();
        if (previousPhoto == null) {
            editor.remove(PHOTO_STATE);
        } else {
            editor.putString(PHOTO_STATE, previousPhoto);
        }
        assertTrue(editor.commit());
        for (File photo : photos) {
            photo.delete();
        }
    }

    @Test
    public void failedReplacementAfterRecreationRestoresOriginalPreview() throws Exception {
        File original = createPhoto(12);
        File replacement = createPhoto(0);
        try (TestActivity scenario = launchWithPhoto(original)) {
            recreateWithPendingPhoto(scenario, replacement);

            assertDisplayedPhoto(scenario, original, 12);
            assertTrue(original.exists());
            assertFalse(replacement.exists());
        }
    }

    @Test
    public void successfulReplacementAfterRecreationCommitsNewPhoto() throws Exception {
        File original = createPhoto(12);
        File replacement = createPhoto(24);
        try (TestActivity scenario = launchWithPhoto(original)) {
            recreateWithPendingPhoto(scenario, replacement);

            assertDisplayedPhoto(scenario, replacement, 24);
            assertFalse(original.exists());
            assertTrue(replacement.exists());
        }
    }

    @Test
    public void unavailableOriginalAndReplacementFinishWithEmptyState() throws Exception {
        File original = createPhoto(12);
        File replacement = createPhoto(0);
        try (TestActivity scenario = launchWithPhoto(original)) {
            assertTrue(original.delete());
            recreateWithPendingPhoto(scenario, replacement);

            awaitPhotoLoading(scenario);
            assertNull(preferences.getString(PHOTO_STATE, null));
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.empty_state).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.preview_state).getVisibility());
                assertNull(((ImageView) activity.findViewById(R.id.photo_preview)).getDrawable());
            });
        }
    }

    private File createPhoto(int width) throws Exception {
        File directory = new File(context.getFilesDir(), "photos");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File photo = File.createTempFile("capture_restore_test_", ".jpg", directory);
        photos.add(photo);
        if (width > 0) {
            Bitmap bitmap = Bitmap.createBitmap(width, 8, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.GREEN);
            try (FileOutputStream output = new FileOutputStream(photo)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output));
            } finally {
                bitmap.recycle();
            }
        }
        return photo;
    }

    private Uri photoUri(File photo) {
        return FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", photo);
    }

    private TestActivity launchWithPhoto(File photo) {
        assertTrue(preferences.edit().putString(PHOTO_STATE, photoUri(photo).toString()).commit());
        TestActivity scenario = TestActivity.launch(context);
        try {
            assertDisplayedPhoto(scenario, photo, 12);
            return scenario;
        } catch (AssertionError error) {
            scenario.close();
            throw error;
        }
    }

    private void recreateWithPendingPhoto(TestActivity scenario, File photo)
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            scenario.onActivity(activity -> {
                ExecutorService executor = (ExecutorService) readField(activity, "imageExecutor");
                // Hold decoding until recreation saves the in-flight replacement request.
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            });
            assertTrue("Image executor did not start", started.await(5, TimeUnit.SECONDS));
            scenario.onActivity(activity -> {
                try {
                    Method loadPhoto = MainActivity.class.getDeclaredMethod("loadPhoto", Uri.class);
                    loadPhoto.setAccessible(true);
                    loadPhoto.invoke(activity, photoUri(photo));
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            });
            scenario.recreate();
        } finally {
            release.countDown();
        }
    }

    private static Object readField(MainActivity activity, String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertDisplayedPhoto(TestActivity scenario, File photo, int width) {
        awaitPhotoLoading(scenario);
        assertEquals(photoUri(photo).toString(), preferences.getString(PHOTO_STATE, null));
        scenario.onActivity(activity -> {
            assertEquals(View.VISIBLE, activity.findViewById(R.id.preview_state).getVisibility());
            ImageView preview = activity.findViewById(R.id.photo_preview);
            assertNotNull(preview.getDrawable());
            assertEquals(width, ((BitmapDrawable) preview.getDrawable()).getBitmap().getWidth());
        });
    }

    // ActivityScenario 1.5 assumes the old Activity is destroyed before its replacement is
    // created. Platform monitors also handle devices that create the replacement first.
    private static final class TestActivity implements AutoCloseable {
        private final Instrumentation instrumentation =
                InstrumentationRegistry.getInstrumentation();
        private MainActivity activity;

        private static TestActivity launch(Context context) {
            TestActivity testActivity = new TestActivity();
            Intent intent = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            testActivity.activity = (MainActivity) testActivity.instrumentation.startActivitySync(intent);
            return testActivity;
        }

        private void onActivity(Consumer<MainActivity> action) {
            instrumentation.runOnMainSync(() -> action.accept(activity));
        }

        private void recreate() {
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                    MainActivity.class.getName(), null, false);
            try {
                onActivity(MainActivity::recreate);
                MainActivity replacement = (MainActivity) monitor.waitForActivityWithTimeout(5000);
                assertNotNull("Activity was not recreated", replacement);
                activity = replacement;
                instrumentation.waitForIdleSync();
            } finally {
                instrumentation.removeMonitor(monitor);
            }
        }

        @Override
        public void close() {
            onActivity(MainActivity::finish);
            instrumentation.waitForIdleSync();
        }
    }

    private static void awaitPhotoLoading(TestActivity scenario) {
        AtomicBoolean finished = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            scenario.onActivity(activity -> finished.set(
                    activity.findViewById(R.id.loading_overlay).getVisibility() == View.GONE));
            if (finished.get()) {
                return;
            }
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Photo loading did not finish within five seconds");
    }
}
