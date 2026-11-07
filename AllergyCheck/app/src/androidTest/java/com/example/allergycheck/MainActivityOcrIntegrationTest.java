package com.example.allergycheck;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises the home photo flow with real, on-device OCR rather than hand-built OCR results. */
@RunWith(AndroidJUnit4.class)
public class MainActivityOcrIntegrationTest {
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
        assertTrue(preferences.edit().remove(PHOTO_STATE).commit());
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
    public void importButtonOpensPhotoPickerAndRecognizesReturnedPhoto() throws Exception {
        File photo = createLabel("INGREDIENTS:", "Sugar, Cocoa Butter, Salt.");
        try (TestActivity scenario = TestActivity.launch(context)) {
            Intent pickerIntent = pickPhoto(scenario, R.id.import_button, MediaStore.ACTION_PICK_IMAGES,
                    Activity.RESULT_OK, photoUri(photo));

            assertPhotoPickerIntent(pickerIntent);
            awaitMessage(scenario, "• Sugar", "• Cocoa Butter", "• Salt");
            assertEquals(photoUri(photo).toString(), preferences.getString(PHOTO_STATE, null));
        }
    }

    @Test
    public void cancelingReplacementPhotoPickerKeepsOriginalPhoto() throws Exception {
        File photo = createLabel("INGREDIENTS:", "Sugar, Cocoa Butter, Salt.");
        try (TestActivity scenario = TestActivity.launch(context)) {
            pickPhoto(scenario, R.id.import_button, MediaStore.ACTION_PICK_IMAGES,
                    Activity.RESULT_OK, photoUri(photo));
            awaitMessage(scenario, "• Sugar");
            scenario.onActivity(activity -> dialog(activity).dismiss());

            Intent pickerIntent = pickPhoto(scenario, R.id.replace_button, MediaStore.ACTION_PICK_IMAGES,
                    Activity.RESULT_CANCELED, null);

            assertPhotoPickerIntent(pickerIntent);
            assertEquals(photoUri(photo).toString(), preferences.getString(PHOTO_STATE, null));
            assertTrue(photo.exists());
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.preview_state).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.loading_overlay).getVisibility());
                AlertDialog resultDialog = dialog(activity);
                assertTrue(resultDialog == null || !resultDialog.isShowing());
            });
        }
    }

    @Test
    public void unreadablePhotoPickerResultCanRetryPhotoPicker() throws Exception {
        File unavailablePhoto = createLabel("INGREDIENTS:", "Oats, Honey.");
        Uri unavailableUri = photoUri(unavailablePhoto);
        assertTrue(unavailablePhoto.delete());
        File validPhoto = createLabel("INGREDIENTS:", "Sugar, Cocoa Butter, Salt.");
        try (TestActivity scenario = TestActivity.launch(context)) {
            Intent photoPickerIntent = pickPhoto(scenario, R.id.import_button,
                    MediaStore.ACTION_PICK_IMAGES, Activity.RESULT_OK, unavailableUri);
            assertPhotoPickerIntent(photoPickerIntent);
            awaitPhotoRetry(scenario);

            Intent retryIntent = pickPhoto(scenario,
                    com.google.android.material.R.id.snackbar_action, MediaStore.ACTION_PICK_IMAGES,
                    Activity.RESULT_OK, photoUri(validPhoto));

            assertPhotoPickerIntent(retryIntent);
            awaitMessage(scenario, "• Sugar", "• Cocoa Butter", "• Salt");
            assertEquals(photoUri(validPhoto).toString(), preferences.getString(PHOTO_STATE, null));
        }
    }

    @Test
    public void selectedLabelAutomaticallyShowsExtractedIngredients() throws Exception {
        File photo = createLabel("INGREDIENTS:", "Sugar, Cocoa Butter, Salt.");
        try (TestActivity scenario = TestActivity.launch(context)) {
            loadPhoto(scenario, photo);

            String message = awaitMessage(scenario, "• Sugar", "• Cocoa Butter", "• Salt");
            assertFalse(message.contains("No ingredients found"));
            assertEquals(photoUri(photo).toString(), preferences.getString(PHOTO_STATE, null));
            scenario.onActivity(activity -> assertEquals(View.VISIBLE,
                    activity.findViewById(R.id.preview_state).getVisibility()));
        }
    }

    @Test
    public void photoWithoutIngredientHeadingShowsRecognizedText() throws Exception {
        File photo = createLabel("Nutrition Facts", "Calories 120");
        try (TestActivity scenario = TestActivity.launch(context)) {
            loadPhoto(scenario, photo);

            awaitMessage(scenario, "No ingredients found", "Calories", "120");
        }
    }

    @Test
    public void blankPhotoShowsNoTextMessage() throws Exception {
        File photo = createLabel();
        try (TestActivity scenario = TestActivity.launch(context)) {
            loadPhoto(scenario, photo);

            awaitMessage(scenario, context.getString(R.string.ocr_empty_result));
        }
    }

    @Test
    public void replacingAndRemovingPhotoClearPreviousResultDialog() throws Exception {
        File original = createLabel("INGREDIENTS:", "Sugar, Cocoa Butter, Salt.");
        File replacement = createLabel("INGREDIENTS:", "Oats, Honey.");
        try (TestActivity scenario = TestActivity.launch(context)) {
            loadPhoto(scenario, original);
            awaitMessage(scenario, "• Sugar");
            AtomicReference<AlertDialog> originalDialog = new AtomicReference<>();
            scenario.onActivity(activity -> originalDialog.set(dialog(activity)));

            loadPhoto(scenario, replacement);
            scenario.onActivity(activity -> assertFalse(originalDialog.get().isShowing()));
            String replacementMessage = awaitMessage(scenario, "• Oats", "• Honey");
            assertFalse(replacementMessage.toLowerCase(Locale.ROOT).contains("sugar"));
            scenario.onActivity(activity -> {
                AlertDialog resultDialog = dialog(activity);
                invoke(activity, "removePhoto", new Class<?>[0]);
                assertFalse(resultDialog.isShowing());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.empty_state).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.preview_state).getVisibility());
            });
            assertFalse(preferences.contains(PHOTO_STATE));
        }
    }

    private File createLabel(String... lines) throws Exception {
        File directory = new File(context.getFilesDir(), "photos");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File photo = File.createTempFile("capture_ocr_test_", ".jpg", directory);
        photos.add(photo);
        Bitmap bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(54);
        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], 48, 100 + i * 90, paint);
        }
        try (FileOutputStream output = new FileOutputStream(photo)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output));
        } finally {
            bitmap.recycle();
        }
        return photo;
    }

    private Uri photoUri(File photo) {
        return FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", photo);
    }

    private void loadPhoto(TestActivity scenario, File photo) {
        scenario.onActivity(activity -> invoke(activity, "loadPhoto",
                new Class<?>[]{Uri.class}, photoUri(photo)));
    }

    private static Intent pickPhoto(TestActivity scenario, int buttonId, String action,
                                    int resultCode, Uri uri) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<Intent> launchedIntent = new AtomicReference<>();
        Intent resultData = uri == null ? null : new Intent().setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Instrumentation.ActivityMonitor picker = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!action.equals(intent.getAction())) {
                    return null;
                }
                launchedIntent.set(new Intent(intent));
                return new Instrumentation.ActivityResult(resultCode, resultData);
            }
        };
        instrumentation.addMonitor(picker);
        try {
            scenario.onActivity(activity -> {
                View button = activity.findViewById(buttonId);
                assertTrue("Photo action must be visible", button.isShown());
                assertTrue(button.performClick());
            });
            instrumentation.waitForIdleSync();
            assertNotNull("Photo action must launch " + action, launchedIntent.get());
            return launchedIntent.get();
        } finally {
            instrumentation.removeMonitor(picker);
        }
    }

    private static void assertPhotoPickerIntent(Intent intent) {
        assertEquals(MediaStore.ACTION_PICK_IMAGES, intent.getAction());
        assertEquals("image/*", intent.getType());
    }

    private void awaitPhotoRetry(TestActivity scenario) {
        AtomicReference<String> message = new AtomicReference<>("");
        AtomicReference<Boolean> actionVisible = new AtomicReference<>(false);
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            scenario.onActivity(activity -> {
                TextView text = activity.findViewById(com.google.android.material.R.id.snackbar_text);
                TextView action = activity.findViewById(com.google.android.material.R.id.snackbar_action);
                message.set(text != null && text.isShown() ? text.getText().toString() : "");
                actionVisible.set(action != null && action.isShown()
                        && context.getString(R.string.try_again).contentEquals(action.getText()));
            });
            if (context.getString(R.string.photo_error).equals(message.get())
                    && actionVisible.get()) {
                return;
            }
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Missing photo retry action. Last message: " + message.get());
    }

    private static void invoke(MainActivity activity, String name,
                               Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static AlertDialog dialog(MainActivity activity) {
        try {
            Field field = MainActivity.class.getDeclaredField("ingredientDialog");
            field.setAccessible(true);
            return (AlertDialog) field.get(activity);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String awaitMessage(TestActivity scenario, String... expectedParts) {
        AtomicReference<String> message = new AtomicReference<>("");
        long deadline = SystemClock.uptimeMillis() + 15000;
        do {
            scenario.onActivity(activity -> {
                AlertDialog dialog = dialog(activity);
                TextView text = dialog == null ? null : dialog.findViewById(android.R.id.message);
                message.set(dialog != null && dialog.isShowing() && text != null
                        ? text.getText().toString() : "");
            });
            boolean matches = true;
            for (String expected : expectedParts) {
                matches &= message.get().toLowerCase(Locale.ROOT)
                        .contains(expected.toLowerCase(Locale.ROOT));
            }
            if (matches) {
                return message.get();
            }
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Expected OCR dialog did not appear. Last message: " + message.get());
    }

    // Matches the platform-monitor lifecycle strategy used by MainActivityPhotoRestoreTest.
    private static final class TestActivity implements AutoCloseable {
        private final Instrumentation instrumentation =
                InstrumentationRegistry.getInstrumentation();
        private MainActivity activity;

        private static TestActivity launch(Context context) {
            TestActivity testActivity = new TestActivity();
            Intent intent = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            testActivity.activity = (MainActivity) testActivity.instrumentation.startActivitySync(intent);
            assertNotNull(testActivity.activity);
            testActivity.instrumentation.waitForIdleSync();
            return testActivity;
        }

        private void onActivity(Consumer<MainActivity> action) {
            instrumentation.runOnMainSync(() -> action.accept(activity));
        }

        @Override
        public void close() {
            onActivity(MainActivity::finish);
            instrumentation.waitForIdleSync();
        }
    }
}
