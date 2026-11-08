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
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
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
            startCheck(scenario);
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
            startCheck(scenario);
            awaitMessage(scenario, "• Sugar");
            // Back from a check returns to the photo preview with its actions.
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            // Let the page fade finish; a fading view is kept visible until its fade ends.
            SystemClock.sleep(1000);

            Intent pickerIntent = pickPhoto(scenario, R.id.replace_button, MediaStore.ACTION_PICK_IMAGES,
                    Activity.RESULT_CANCELED, null);

            assertPhotoPickerIntent(pickerIntent);
            assertEquals(photoUri(photo).toString(), preferences.getString(PHOTO_STATE, null));
            assertTrue(photo.exists());
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.preview_state).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.loading_overlay).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.analysis_group).getVisibility());
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
            startCheck(scenario);
            awaitMessage(scenario, "• Sugar", "• Cocoa Butter", "• Salt");
            assertEquals(photoUri(validPhoto).toString(), preferences.getString(PHOTO_STATE, null));
        }
    }

    @Test
    public void selectedLabelIsOnlyReadAfterStartingCheck() throws Exception {
        File photo = createLabel("INGREDIENTS:", "Sugar, Cocoa Butter, Salt.");
        try (TestActivity scenario = TestActivity.launch(context)) {
            loadPhoto(scenario, photo);
            awaitPhotoReady(scenario);
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.analysis_group).getVisibility());
                assertTrue(activity.findViewById(R.id.start_analysis_button).isShown());
            });

            startCheck(scenario);
            String message = awaitMessage(scenario, "• Sugar", "• Cocoa Butter", "• Salt");
            assertFalse(message.contains("No ingredients found"));
            assertEquals(photoUri(photo).toString(), preferences.getString(PHOTO_STATE, null));
            scenario.onActivity(activity -> {
                // The check replaces the home content; the photo moves to the strip at the top.
                assertEquals(View.GONE, activity.findViewById(R.id.intro_group).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.photo_card).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.tips_group).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.photo_strip_scroll).getVisibility());
                ViewGroup strip = activity.findViewById(R.id.photo_strip);
                assertEquals(1, strip.getChildCount());
                assertTrue(activity.findViewById(R.id.restart_button).isShown());
            });
        }
    }

    @Test
    public void photoWithoutIngredientHeadingShowsRecognizedText() throws Exception {
        File photo = createLabel("Nutrition Facts", "Calories 120");
        try (TestActivity scenario = TestActivity.launch(context)) {
            loadPhoto(scenario, photo);
            startCheck(scenario);

            awaitMessage(scenario, "No ingredients found", "Calories", "120");
        }
    }

    @Test
    public void blankPhotoShowsNoTextMessage() throws Exception {
        File photo = createLabel();
        try (TestActivity scenario = TestActivity.launch(context)) {
            loadPhoto(scenario, photo);
            startCheck(scenario);

            awaitMessage(scenario, context.getString(R.string.ocr_empty_result));
        }
    }

    @Test
    public void replacingPhotoDuringCheckRechecksAndRestartClearsEverything() throws Exception {
        File original = createLabel("INGREDIENTS:", "Sugar, Cocoa Butter, Salt.");
        File replacement = createLabel("INGREDIENTS:", "Oats, Honey.");
        try (TestActivity scenario = TestActivity.launch(context)) {
            loadPhoto(scenario, original);
            startCheck(scenario);
            awaitMessage(scenario, "• Sugar");

            // Changing the photo from the strip re-reads the new one without another tap.
            loadPhoto(scenario, replacement);
            String replacementMessage = awaitMessage(scenario, "• Oats", "• Honey");
            assertFalse(replacementMessage.toLowerCase(Locale.ROOT).contains("sugar"));
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.restart_button).performClick());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.empty_state).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.preview_state).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.analysis_group).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.intro_group).getVisibility());
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

    /** Waits until the added photo is decoded and the check can start. */
    private static void awaitPhotoReady(TestActivity scenario) {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            scenario.onActivity(activity -> {
                View button = activity.findViewById(R.id.start_analysis_button);
                ready.set(button.isShown() && button.isEnabled()
                        && ((ImageView) activity.findViewById(R.id.photo_preview)).getDrawable() != null);
            });
            if (ready.get()) {
                return;
            }
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Photo was not ready to check");
    }

    /** Taps "Check ingredients" once the photo is ready. */
    private static void startCheck(TestActivity scenario) {
        awaitPhotoReady(scenario);
        scenario.onActivity(activity ->
                assertTrue(activity.findViewById(R.id.start_analysis_button).performClick()));
    }

    /** All text shown in the in-page result, or "" while it is hidden. */
    private static String resultText(MainActivity activity) {
        View result = activity.findViewById(R.id.analysis_result);
        if (!result.isShown()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        appendText(result, out);
        return out.toString();
    }

    private static void appendText(View view, StringBuilder out) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView) {
            out.append(((TextView) view).getText()).append('\n');
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                appendText(group.getChildAt(i), out);
            }
        }
    }

    private static String awaitMessage(TestActivity scenario, String... expectedParts) {
        AtomicReference<String> message = new AtomicReference<>("");
        long deadline = SystemClock.uptimeMillis() + 15000;
        do {
            scenario.onActivity(activity -> message.set(resultText(activity)));
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
        throw new AssertionError("Expected result did not appear. Last result: " + message.get());
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
