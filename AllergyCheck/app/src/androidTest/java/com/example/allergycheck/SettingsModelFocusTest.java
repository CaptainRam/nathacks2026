package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;

import androidx.lifecycle.Lifecycle;
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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Stable model-action identity and advisory window-return hints; no app-driven focus actions. */
@RunWith(AndroidJUnit4.class)
public class SettingsModelFocusTest {
    private Context context;
    private SharedPreferences modelPreferences;
    private Map<String, ?> originalModelPreferences;
    private String originalMode;
    private File modelDirectory;
    private File backupDirectory;
    private File fixture;
    private boolean isolated;
    private boolean preferencesChanged;

    @Before
    public void isolateInstalledModel() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        modelPreferences = context.getSharedPreferences("local_model", Context.MODE_PRIVATE);
        assumeTrue("An existing download must remain untouched",
                modelPreferences.getLong("download_id", -1) == -1);
        boolean[] busy = {false};
        onMain(() -> busy[0] = LocalModelManager.get(context).getStatus().isBusy());
        assumeFalse("An active model installation must remain untouched", busy[0]);
        originalModelPreferences = new HashMap<>(modelPreferences.getAll());
        originalMode = context.getSharedPreferences("analysis_preferences", Context.MODE_PRIVATE)
                .getString("mode", null);
        modelDirectory = LocalModelManager.modelDirectory(context);
        backupDirectory = new File(modelDirectory.getParentFile(),
                "llm-focus-test-backup-" + UUID.randomUUID());
        if (modelDirectory.exists()) {
            // Preserve a real model with an atomic rename, without reading/copying its contents.
            Files.move(modelDirectory.toPath(), backupDirectory.toPath(),
                    StandardCopyOption.ATOMIC_MOVE);
        }
        isolated = true;
        assertTrue(modelDirectory.mkdirs());
        fixture = new File(modelDirectory, ModelFiles.MODEL_FILE);
        Files.write(fixture.toPath(), new byte[] {1});
        preferencesChanged = true;
        assertTrue(modelPreferences.edit().clear().commit());
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.LOCAL_AI);
        onMain(() -> LocalModelManager.get(context).refresh());
    }

    @After
    public void restoreInstalledModel() throws Exception {
        try {
            if (isolated) {
                if (modelDirectory.exists()) {
                    try (Stream<Path> paths = Files.walk(modelDirectory.toPath())) {
                        for (Path path : (Iterable<Path>) paths.sorted(
                                Comparator.reverseOrder())::iterator) Files.delete(path);
                    }
                }
                if (backupDirectory.exists()) {
                    Files.move(backupDirectory.toPath(), modelDirectory.toPath(),
                            StandardCopyOption.ATOMIC_MOVE);
                }
            }
        } finally {
            if (preferencesChanged) {
                restorePreferences(modelPreferences, originalModelPreferences);
                SharedPreferences.Editor editor = context.getSharedPreferences(
                        "analysis_preferences", Context.MODE_PRIVATE).edit();
                if (originalMode == null) editor.remove("mode");
                else editor.putString("mode", originalMode);
                assertTrue(editor.commit());
                onMain(() -> LocalModelManager.get(context).refresh());
            }
        }
    }

    @Test
    public void cancelKeepsTheOriginalDeleteActionWithoutForcingFocus() {
        java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<SettingsActivity> scenario = launch()) {
            View original = openConfirmation(scenario, forced);
            clickDialogButton(scenario, android.R.id.button2);
            awaitClosed(scenario);
            scenario.onActivity(activity -> {
                assertSame(original, action(activity));
                assertDeleteAction(activity);
                assertTrue(fixture.isFile());
            });
            assertNoForcedActions(forced);
        }
    }

    @Test
    public void systemBackKeepsTheOriginalDeleteAction() {
        java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<SettingsActivity> scenario = launch()) {
            View original = openConfirmation(scenario, forced);
            scenario.onActivity(activity -> dialog(activity).requireDialog().onBackPressed());
            awaitClosed(scenario);
            scenario.onActivity(activity -> {
                assertSame(original, action(activity));
                assertDeleteAction(activity);
                assertTrue(fixture.isFile());
            });
            assertNoForcedActions(forced);
        }
    }

    @Test
    public void deletionChangesTheSameActionToDownload() {
        java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<SettingsActivity> scenario = launch()) {
            View original = openConfirmation(scenario, forced);
            clickDialogButton(scenario, android.R.id.button1);
            awaitClosed(scenario);
            scenario.onActivity(activity -> {
                assertSame("The node retained in the screen reader's window history must survive",
                        original, action(activity));
                assertTrue(action(activity).isShown());
                assertTrue(action(activity).isEnabled());
                assertEquals(activity.getString(R.string.model_settings_download),
                        ((android.widget.TextView) action(activity)).getText().toString());
                assertEquals(activity.getString(R.string.a11y_model_download),
                        action(activity).getContentDescription().toString());
            });
            assertFalse(fixture.exists());
            assertNoForcedActions(forced);
        }
    }

    @Test
    public void rotatingTheOpenConfirmationRetainsTheReturnCandidate() {
        java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<SettingsActivity> scenario = launch()) {
            openConfirmation(scenario, forced);
            scenario.recreate();
            View[] recreated = new View[1];
            scenario.onActivity(activity -> {
                observeForcedActions(activity, forced);
                recreated[0] = action(activity);
                assertNotNull(dialog(activity));
                assertTrue(hasInitialHint(action(activity)));
            });
            clickDialogButton(scenario, android.R.id.button2);
            awaitClosed(scenario);
            scenario.onActivity(activity -> {
                assertSame(recreated[0], action(activity));
                assertDeleteAction(activity);
            });
            assertTrue(fixture.isFile());
            assertNoForcedActions(forced);
        }
    }

    @Test
    public void sectionRoundTripRevokesTheReturnCandidate() {
        java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<SettingsActivity> scenario = launch()) {
            openConfirmation(scenario, forced);
            clickDialogButton(scenario, android.R.id.button2, activity -> {
                activity.findViewById(R.id.section_allergens_button).performClick();
                activity.findViewById(R.id.section_model_button).performClick();
            });
            awaitClosed(scenario);
            scenario.onActivity(activity -> assertFalse(hasInitialHint(action(activity))));
            assertNoForcedActions(forced);
        }
    }

    @Test
    public void detectionLocationRoundTripRevokesTheReturnCandidate() {
        java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<SettingsActivity> scenario = launch()) {
            openConfirmation(scenario, forced);
            clickDialogButton(scenario, android.R.id.button2, activity -> {
                activity.findViewById(R.id.detection_location_cloud).performClick();
                activity.findViewById(R.id.detection_location_local).performClick();
            });
            awaitClosed(scenario);
            scenario.onActivity(activity -> assertFalse(hasInitialHint(action(activity))));
            assertNoForcedActions(forced);
        }
    }

    @Test
    public void backgroundingRevokesTheReturnCandidateAfterResume() {
        verifyPauseRevokesReturnCandidate(Lifecycle.State.CREATED);
    }

    @Test
    public void pauseWithoutStoppingRevokesTheReturnCandidateAfterResume() {
        verifyPauseRevokesReturnCandidate(Lifecycle.State.STARTED);
    }

    @Test
    public void aNewReadingPositionConsumesTheFallbackWithoutMovingFocus() {
        java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<SettingsActivity> scenario = launch()) {
            openConfirmation(scenario, forced);
            scenario.onActivity(activity -> {
                dialog(activity).dismissNow();
                assertTrue(hasInitialHint(action(activity)));
                ViewGroup root = activity.findViewById(R.id.settings);
                android.view.accessibility.AccessibilityEvent event =
                        android.view.accessibility.AccessibilityEvent.obtain(
                                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                try {
                    // Exercise the platform child-event callback without sending a fabricated
                    // event to the service or moving any accessibility/input focus ourselves.
                    root.getAccessibilityDelegate().onRequestSendAccessibilityEvent(root,
                            activity.findViewById(R.id.model_details_button), event);
                } finally {
                    event.recycle();
                }
                assertFalse("The service's chosen reading position must consume the fallback",
                        hasInitialHint(action(activity)));
            });
            assertNoForcedActions(forced);
        }
    }

    private void verifyPauseRevokesReturnCandidate(Lifecycle.State pausedState) {
        java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
        try (ActivityScenario<SettingsActivity> scenario = launch()) {
            openConfirmation(scenario, forced);
            scenario.onActivity(activity -> {
                dialog(activity).dismissNow();
                assertTrue("The window return must have a real candidate before pausing",
                        hasInitialHint(action(activity)));
            });
            scenario.moveToState(pausedState);
            scenario.onActivity(activity -> {
                SettingsActivity.ModelReturnFocus state = field(activity, "modelReturnFocus");
                assertEquals("Pausing must discard the intent, not only hide its node property",
                        View.NO_ID, state.targetId);
            });
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> assertFalse(hasInitialHint(action(activity))));
            assertNoForcedActions(forced);
        }
    }

    private ActivityScenario<SettingsActivity> launch() {
        return ActivityScenario.launch(new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SHOW_MODEL, true));
    }

    private static View openConfirmation(ActivityScenario<SettingsActivity> scenario,
                                         java.util.concurrent.atomic.AtomicInteger forced) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            View[] opened = new View[1];
            scenario.onActivity(activity -> {
                if (!activity.hasWindowFocus()) return;
                observeForcedActions(activity, forced);
                assertDeleteAction(activity);
                assertFalse(hasInitialHint(action(activity)));
                assertTrue(action(activity).performAccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK, null));
                assertNotNull(dialog(activity));
                assertTrue("The returning button is an advisory candidate before the dialog closes",
                        hasInitialHint(action(activity)));
                opened[0] = action(activity);
            });
            if (opened[0] != null) return opened[0];
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Settings never opened its model confirmation");
    }

    private static View action(SettingsActivity activity) {
        return activity.findViewById(R.id.model_download_button);
    }

    private static void assertDeleteAction(SettingsActivity activity) {
        assertTrue(action(activity).isShown());
        assertTrue(action(activity).isEnabled());
        assertEquals(activity.getString(R.string.model_settings_delete),
                ((android.widget.TextView) action(activity)).getText().toString());
        assertEquals(activity.getString(R.string.a11y_model_delete),
                action(activity).getContentDescription().toString());
    }

    private static SettingsActivity.DeleteModelDialog dialog(SettingsActivity activity) {
        return (SettingsActivity.DeleteModelDialog) activity.getSupportFragmentManager()
                .findFragmentByTag(SettingsActivity.DeleteModelDialog.TAG);
    }

    private static boolean hasInitialHint(View view) {
        AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
        try { return info.hasRequestInitialAccessibilityFocus(); }
        finally { info.recycle(); }
    }

    private static void observeForcedActions(SettingsActivity activity,
                                             java.util.concurrent.atomic.AtomicInteger forced) {
        View target = action(activity);
        View.AccessibilityDelegate original = target.getAccessibilityDelegate();
        androidx.core.view.ViewCompat.setAccessibilityDelegate(target,
                new androidx.core.view.AccessibilityDelegateCompat(
                        original == null ? new View.AccessibilityDelegate() : original) {
                    @Override public boolean performAccessibilityAction(View host, int action,
                                                                        Bundle arguments) {
                        if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                                if (frame.getClassName().startsWith(SettingsActivity.class.getName())) {
                                    forced.incrementAndGet();
                                    break;
                                }
                            }
                        }
                        // A service may still choose focus normally. Do not intercept that action.
                        return super.performAccessibilityAction(host, action, arguments);
                    }
                });
    }

    private static void clickDialogButton(ActivityScenario<SettingsActivity> scenario, int id) {
        clickDialogButton(scenario, id, null);
    }

    private static void clickDialogButton(ActivityScenario<SettingsActivity> scenario, int id,
                                         Consumer<SettingsActivity> afterClick) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            boolean[] clicked = {false};
            scenario.onActivity(activity -> {
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    if (root == activity.getWindow().getDecorView()
                            || !root.isShown() || !root.hasWindowFocus()) continue;
                    View button = root.findViewById(id);
                    if (button != null && button.isShown() && button.isEnabled()) {
                        clicked[0] = button.performAccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLICK, null);
                        if (clicked[0] && afterClick != null) afterClick.accept(activity);
                        return;
                    }
                }
            });
            if (clicked[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No focused dialog accepted ACTION_CLICK for " + id);
    }

    private static void awaitClosed(ActivityScenario<SettingsActivity> scenario) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            boolean[] closed = {false};
            scenario.onActivity(activity -> closed[0] = dialog(activity) == null
                    && activity.hasWindowFocus());
            if (closed[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The model confirmation did not return its window");
    }

    private static void assertNoForcedActions(java.util.concurrent.atomic.AtomicInteger forced) {
        SystemClock.sleep(300);
        assertEquals("The app must let the screen reader choose its own reading position",
                0, forced.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(SettingsActivity activity, String name) {
        try {
            Field field = SettingsActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(activity);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    @SuppressWarnings("unchecked")
    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key,
                    new HashSet<>((Set<String>) value));
        }
        assertTrue(editor.commit());
    }
}
