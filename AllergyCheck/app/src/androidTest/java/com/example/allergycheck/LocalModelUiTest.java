package com.example.allergycheck;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.ingredients.LocalModelManager;
import com.example.allergycheck.ingredients.ModelFiles;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/** Exercises model management without networking or loading a real inference model. */
@RunWith(AndroidJUnit4.class)
public class LocalModelUiTest {
    private static final String PHOTO_STATE = "selected_photo";

    private Context context;
    private SharedPreferences modelPreferences;
    private SharedPreferences allergenPreferences;
    private SharedPreferences analysisPreferences;
    private Map<String, ?> originalModelPreferences;
    private String originalPhoto;
    private String originalAnalysisMode;
    private File modelDirectory;
    private File backupDirectory;
    private boolean isolatedModelDirectory;
    private boolean preferencesChanged;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        modelPreferences = context.getSharedPreferences("local_model", Context.MODE_PRIVATE);
        // Even a completed persisted job may be about to publish a model. Leave all such jobs alone.
        assumeTrue("An existing model download must not be disturbed",
                modelPreferences.getLong("download_id", -1) == -1);
        AtomicReference<LocalModelManager.Status> status = new AtomicReference<>();
        onMain(() -> status.set(LocalModelManager.get(context).getStatus()));
        assumeFalse("Model installation must finish before these tests run", status.get().isBusy());

        originalModelPreferences = copyPreferences(modelPreferences);
        allergenPreferences = AllergenPreferences.preferences(context);
        originalPhoto = allergenPreferences.getString(PHOTO_STATE, null);
        analysisPreferences = context.getSharedPreferences("analysis_preferences", Context.MODE_PRIVATE);
        originalAnalysisMode = analysisPreferences.getString("mode", null);
        modelDirectory = LocalModelManager.modelDirectory(context);
        backupDirectory = new File(modelDirectory.getParentFile(),
                "llm-ui-test-backup-" + UUID.randomUUID());
        if (modelDirectory.exists()) {
            // Models can be hundreds of MB; never read, copy, or delete the user's existing model.
            Files.move(modelDirectory.toPath(), backupDirectory.toPath(),
                    StandardCopyOption.ATOMIC_MOVE);
        }
        isolatedModelDirectory = true;
        preferencesChanged = true;
        assertTrue(modelPreferences.edit().clear().commit());
        assertTrue(allergenPreferences.edit().remove(PHOTO_STATE).commit());
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.LOCAL_AI);
        refreshManager();
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (isolatedModelDirectory) {
                deleteTestDirectory(modelDirectory.toPath());
                if (backupDirectory.exists()) {
                    Files.move(backupDirectory.toPath(), modelDirectory.toPath(),
                            StandardCopyOption.ATOMIC_MOVE);
                }
            }
        } finally {
            if (preferencesChanged) {
                restorePreferences(modelPreferences, originalModelPreferences);
                SharedPreferences.Editor editor = allergenPreferences.edit();
                if (originalPhoto == null) {
                    editor.remove(PHOTO_STATE);
                } else {
                    editor.putString(PHOTO_STATE, originalPhoto);
                }
                assertTrue(editor.commit());
                SharedPreferences.Editor analysisEditor = analysisPreferences.edit();
                if (originalAnalysisMode == null) {
                    analysisEditor.remove("mode");
                } else {
                    analysisEditor.putString("mode", originalAnalysisMode);
                }
                assertTrue(analysisEditor.commit());
                refreshManager();
            }
        }
    }

    @Test
    public void firstLaunchOffersModelOnceAndMissingBannerCanReopenPrompt() {
        try (TestActivity<MainActivity> screen = TestActivity.launch(context, MainActivity.class)) {
            screen.onActivity(activity -> {
                AlertDialog dialog = introduction(activity);
                assertNotNull("First launch must offer the model", dialog);
                assertTrue(dialog.isShowing());
                assertTrue(hasText(dialog.getWindow().getDecorView(),
                        context.getString(R.string.model_intro_title)));
                assertEquals(context.getString(R.string.model_later),
                        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());
                assertTrue(dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick());
            });
            assertTrue(modelPreferences.getBoolean("intro_shown", false));
            assertFalse("Choosing Later must never enqueue a download",
                    modelPreferences.contains("download_id"));

            screen.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.model_download_card).isShown());
                assertTrue(activity.findViewById(R.id.model_status_action).isEnabled());
                assertTrue(activity.findViewById(R.id.model_status_action).performClick());
            });
            screen.onActivity(activity -> {
                AlertDialog dialog = introduction(activity);
                assertNotNull("Missing-model action must reopen the offer", dialog);
                assertTrue(dialog.isShowing());
                assertTrue(dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick());
            });
        }

        try (TestActivity<MainActivity> screen = TestActivity.launch(context, MainActivity.class)) {
            screen.onActivity(activity -> {
                AlertDialog dialog = introduction(activity);
                assertTrue("Later must persist across launches", dialog == null || !dialog.isShowing());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.model_download_card).getVisibility());
                assertTrue(activity.findViewById(R.id.model_status_action).isEnabled());
            });
        }
    }

    @Test
    public void matchingAndCloudHideLocalModelOfferWithoutConsumingIntroduction() {
        for (AnalysisPreferences.Mode mode : new AnalysisPreferences.Mode[] {
                AnalysisPreferences.Mode.MATCHING,
                AnalysisPreferences.Mode.OPENROUTER,
                AnalysisPreferences.Mode.DECISIONS}) {
            AnalysisPreferences.saveMode(context, mode);
            try (TestActivity<MainActivity> screen = TestActivity.launch(context, MainActivity.class)) {
                screen.onActivity(activity -> {
                    AlertDialog dialog = introduction(activity);
                    assertTrue("The local-model prompt is irrelevant in " + mode,
                            dialog == null || !dialog.isShowing());
                    assertEquals(View.GONE, activity.findViewById(R.id.model_download_card).getVisibility());
                });
            }
            assertFalse("Switching to Local AI later should still offer the model",
                    modelPreferences.getBoolean("intro_shown", false));
        }
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.LOCAL_AI);
        try (TestActivity<MainActivity> screen = TestActivity.launch(context, MainActivity.class)) {
            screen.onActivity(activity -> {
                assertNotNull(introduction(activity));
                assertTrue(introduction(activity).isShowing());
                assertTrue(activity.findViewById(R.id.model_download_card).isShown());
                introduction(activity).getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            });
        }
    }

    @Test
    public void settingsTracksManualModelAndDeletesOnlyAfterConfirmation() throws Exception {
        try (TestActivity<SettingsActivity> screen = TestActivity.launch(context, SettingsActivity.class)) {
            screen.onActivity(this::assertMissingSettingsState);
        }

        assertTrue(modelDirectory.isDirectory() || modelDirectory.mkdirs());
        File model = new File(modelDirectory, ModelFiles.MODEL_FILE);
        // Presence detection accepts manually copied nonempty .task files. Never run inference here.
        Files.write(model.toPath(), "UI presence fixture".getBytes(StandardCharsets.UTF_8));
        refreshManager();
        try (TestActivity<SettingsActivity> screen = TestActivity.launch(context, SettingsActivity.class)) {
            screen.onActivity(activity -> {
                assertText(activity, R.id.model_availability, R.string.model_settings_installed);
                assertText(activity, R.id.model_download_status, R.string.model_settings_ready);
                assertTrue(activity.findViewById(R.id.model_download_button).isEnabled());
                assertText(activity, R.id.model_download_button, R.string.model_settings_delete);
                assertTrue(activity.findViewById(R.id.model_download_button).performClick());
            });
            assertTrue("Opening confirmation must not delete a model", model.isFile());
            screen.onActivity(activity -> {
                View dialog = deletionDialogRoot();
                assertNotNull("Delete must open a confirmation dialog", dialog);
                assertTrue(dialog.findViewById(android.R.id.button2).performClick());
            });
            assertTrue("Cancel must keep the model", model.isFile());

            screen.onActivity(activity -> assertTrue(
                    activity.findViewById(R.id.model_download_button).performClick()));
            screen.onActivity(activity -> {
                View dialog = deletionDialogRoot();
                assertNotNull(dialog);
                assertTrue(dialog.findViewById(android.R.id.button1).performClick());
            });
            assertFalse(model.exists());
            screen.onActivity(this::assertMissingSettingsState);
        }
    }

    @Test
    public void installedModelSignalsLocalAnalysisChangeAfterRotationAndSave() throws Exception {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(settingsIntent())) {
            writeManualModelFixture();
            refreshManager();
            assertTrue(LocalModelManager.installedModel(context) != null);
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(
                    activity.findViewById(R.id.save_preferences_button).performClick()));
            assertLocalModelChangedResult(scenario.getResult());
        }
    }

    @Test
    public void deletingModelSignalsLocalAnalysisChangeWhenLeavingWithBack() throws Exception {
        File model = writeManualModelFixture();
        refreshManager();
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(settingsIntent())) {
            scenario.onActivity(activity -> assertTrue(
                    activity.findViewById(R.id.model_download_button).performClick()));
            scenario.onActivity(activity -> {
                View dialog = deletionDialogRoot();
                assertNotNull(dialog);
                assertTrue(dialog.findViewById(android.R.id.button1).performClick());
            });
            assertFalse(model.exists());
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            assertLocalModelChangedResult(scenario.getResult());
        }
    }

    @Test
    public void unchangedLocalModelAvailabilityDoesNotSignalConfigurationChanges() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(settingsIntent())) {
            refreshManager();
            refreshManager();
            scenario.onActivity(activity -> assertTrue(
                    activity.findViewById(R.id.save_preferences_button).performClick()));
            assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
        }
    }

    @Test
    public void installingLocalModelDoesNotChangeMatchingConfiguration() throws Exception {
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(settingsIntent())) {
            writeManualModelFixture();
            refreshManager();
            scenario.onActivity(activity -> assertTrue(
                    activity.findViewById(R.id.save_preferences_button).performClick()));
            assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
        }
    }

    private Intent settingsIntent() {
        return new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SHOW_MODEL, true);
    }

    private File writeManualModelFixture() throws IOException {
        assertTrue(modelDirectory.isDirectory() || modelDirectory.mkdirs());
        File model = new File(modelDirectory, ModelFiles.MODEL_FILE);
        // Exercise the existing manual-model presence contract; never load this fixture for inference.
        Files.write(model.toPath(), "UI presence fixture".getBytes(StandardCharsets.UTF_8));
        return model;
    }

    private void assertLocalModelChangedResult(Instrumentation.ActivityResult result) {
        assertEquals(Activity.RESULT_OK, result.getResultCode());
        Intent data = result.getResultData();
        assertNotNull(data);
        assertTrue(data.getBooleanExtra(SettingsActivity.EXTRA_ANALYSIS_CONFIGURATION_CHANGED, false));
        assertTrue(data.getBooleanExtra(SettingsActivity.EXTRA_AI_PROVIDER_CHANGED, false));
        assertFalse(data.getBooleanExtra(SettingsActivity.EXTRA_ALLERGEN_SELECTION_CHANGED, true));
    }

    @Test
    public void downloadPersistsOneJobAndDeleteCancelsIt() {
        // Keep enqueue, duplicate attempt and cancellation in one UI turn: never wait for transfer.
        onMain(() -> {
            LocalModelManager manager = LocalModelManager.get(context);
            try {
                manager.download();
                long firstId = modelPreferences.getLong("download_id", -1);
                assertTrue("A model download must persist its system job ID", firstId > 0);
                assertTrue(manager.getStatus().isBusy());
                manager.download();
                assertEquals("Repeated download actions must reuse the in-flight system job",
                        firstId, modelPreferences.getLong("download_id", -1));
            } finally {
                manager.delete();
            }
            assertFalse(modelPreferences.contains("download_id"));
            assertEquals(LocalModelManager.State.MISSING, manager.getStatus().state);
            assertFalse(manager.getStatus().isInstalled());
        });
    }

    private void assertMissingSettingsState(SettingsActivity activity) {
        assertText(activity, R.id.model_availability, R.string.model_settings_missing);
        assertText(activity, R.id.model_download_status, R.string.model_settings_not_downloaded);
        assertTrue(activity.findViewById(R.id.model_download_button).isEnabled());
        assertText(activity, R.id.model_download_button, R.string.model_settings_download);
    }

    private static void assertText(Activity activity, int viewId, int textId) {
        TextView view = activity.findViewById(viewId);
        assertNotNull(view);
        assertEquals(activity.getString(textId), view.getText().toString());
    }

    private static AlertDialog introduction(MainActivity activity) {
        try {
            Field controllerField = MainActivity.class.getDeclaredField("modelDownloadController");
            controllerField.setAccessible(true);
            Object controller = controllerField.get(activity);
            Field dialogField = ModelDownloadController.class.getDeclaredField("introduction");
            dialogField.setAccessible(true);
            return (AlertDialog) dialogField.get(controller);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private View deletionDialogRoot() {
        String title = context.getString(R.string.model_settings_delete_title);
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (root.isShown() && root.findViewById(android.R.id.button1) != null
                    && hasText(root, title)) {
                return root;
            }
        }
        return null;
    }

    private static boolean hasText(View root, String expected) {
        if (root instanceof TextView && expected.contentEquals(((TextView) root).getText())) {
            return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), expected)) return true;
            }
        }
        return false;
    }

    private void refreshManager() {
        onMain(() -> LocalModelManager.get(context).refresh());
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static Map<String, ?> copyPreferences(SharedPreferences preferences) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            copy.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
            else throw new AssertionError("Unexpected preference value for " + key);
        }
        assertTrue(editor.commit());
    }

    private static void deleteTestDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> entries = Files.walk(directory)) {
            Path[] paths = entries.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path path : paths) Files.delete(path);
        }
    }

    private static final class TestActivity<T extends Activity> implements AutoCloseable {
        private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        private T activity;

        static <T extends Activity> TestActivity<T> launch(Context context, Class<T> activityClass) {
            TestActivity<T> screen = new TestActivity<>();
            Intent intent = new Intent(context, activityClass)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (activityClass.equals(SettingsActivity.class)) {
                intent.putExtra(SettingsActivity.EXTRA_SHOW_MODEL, true);
            }
            screen.activity = activityClass.cast(screen.instrumentation.startActivitySync(intent));
            assertNotNull(screen.activity);
            screen.instrumentation.waitForIdleSync();
            return screen;
        }

        void onActivity(Consumer<T> action) {
            instrumentation.runOnMainSync(() -> action.accept(activity));
            instrumentation.waitForIdleSync();
        }

        @Override
        public void close() {
            onActivity(Activity::finish);
            instrumentation.waitForIdleSync();
        }
    }
}
