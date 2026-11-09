package com.example.allergycheck;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.textfield.TextInputLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

/** Configuration changes without inference or network calls. */
@RunWith(AndroidJUnit4.class)
public class DetectionSettingsTest {
    private Context context;
    private final Map<String, Map<String, ?>> previous = new HashMap<>();

    @Before
    public void isolateConfiguration() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String file : new String[] {"analysis_preferences", "analysis_credentials"}) {
            SharedPreferences preferences = context.getSharedPreferences(file, Context.MODE_PRIVATE);
            previous.put(file, new HashMap<>(preferences.getAll()));
            assertTrue(preferences.edit().clear().commit());
        }
    }

    @After
    public void restoreConfiguration() {
        for (Map.Entry<String, Map<String, ?>> file : previous.entrySet()) {
            SharedPreferences.Editor editor = context.getSharedPreferences(
                    file.getKey(), Context.MODE_PRIVATE).edit().clear();
            for (Map.Entry<String, ?> entry : file.getValue().entrySet()) {
                // Every analysis preference is intentionally stored as a string.
                editor.putString(entry.getKey(), (String) entry.getValue());
            }
            assertTrue(editor.commit());
        }
    }

    private Intent settingsIntent() {
        return new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SHOW_MODEL, true);
    }

    @Test
    public void locationAndModeSelectionsPersistImmediately() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(settingsIntent())) {
            scenario.onActivity(activity -> {
                assertEquals(AnalysisPreferences.Mode.MATCHING, AnalysisPreferences.mode(context));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.detection_local_modes).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.detection_cloud_configuration).getVisibility());
                activity.findViewById(R.id.detection_mode_local_ai).performClick();
                assertEquals(AnalysisPreferences.Mode.LOCAL_AI, AnalysisPreferences.mode(context));
                activity.findViewById(R.id.detection_location_cloud).performClick();
                assertEquals(AnalysisPreferences.Mode.OPENROUTER, AnalysisPreferences.mode(context));
                assertEquals(View.GONE, activity.findViewById(R.id.local_model_section).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.detection_cloud_configuration).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.detection_concurrency_layout).getVisibility());
                activity.findViewById(R.id.detection_mode_decisions).performClick();
                assertEquals(AnalysisPreferences.Mode.DECISIONS, AnalysisPreferences.mode(context));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.detection_threshold_layout).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.detection_concurrency_layout).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.detection_model_layout).getVisibility());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(AnalysisPreferences.Mode.DECISIONS, AnalysisPreferences.mode(context));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.detection_threshold_layout).getVisibility());
            });
        }
    }

    @Test
    public void decisionsSettingsRejectInvalidThresholdBeforeSaving() {
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.DECISIONS);
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(settingsIntent())) {
            scenario.onActivity(activity -> {
                input(activity, R.id.detection_api_key).setText("test-settings-key");
                input(activity, R.id.detection_decisions_model_id).setText("typesafe/example");
                input(activity, R.id.detection_threshold).setText("1.1");
                activity.findViewById(R.id.detection_save_cloud).performClick();
                assertNotNull(((TextInputLayout) activity.findViewById(R.id.detection_threshold_layout)).getError());
                assertEquals("", AnalysisPreferences.openRouterApiKey(context));
                assertEquals("", AnalysisPreferences.decisionsModel(context));
                input(activity, R.id.detection_threshold).setText("0.65");
                activity.findViewById(R.id.detection_save_cloud).performClick();
                assertNull(((TextInputLayout) activity.findViewById(R.id.detection_threshold_layout)).getError());
                assertEquals("test-settings-key", AnalysisPreferences.openRouterApiKey(context));
                assertEquals("typesafe/example", AnalysisPreferences.decisionsModel(context));
                assertEquals(0.65, AnalysisPreferences.decisionsThreshold(context), 0.000001);
                input(activity, R.id.detection_threshold).setText("0");
                activity.findViewById(R.id.detection_save_cloud).performClick();
                assertNull(((TextInputLayout) activity.findViewById(R.id.detection_threshold_layout)).getError());
                assertEquals(0, AnalysisPreferences.decisionsThreshold(context), 0);
            });
        }
    }

    @Test
    public void thresholdNumbersUseLocaleAndRejectPartiallyParsedInput() {
        assertEquals("0,65", SettingsActivity.formatThreshold(0.65, Locale.FRANCE));
        assertEquals(0.65, SettingsActivity.parseThreshold("0,65", Locale.FRANCE), 0.000001);
        assertEquals(0.65, SettingsActivity.parseThreshold("0.65", Locale.US), 0.000001);
        assertEquals(0, SettingsActivity.parseThreshold("0", Locale.FRANCE), 0);
        assertThrows(NumberFormatException.class, () -> SettingsActivity.parseThreshold("0,65", Locale.US));
        assertThrows(NumberFormatException.class, () -> SettingsActivity.parseThreshold("0.65extra", Locale.US));
        assertThrows(NumberFormatException.class, () -> SettingsActivity.parseThreshold("NaN", Locale.US));
        assertThrows(NumberFormatException.class, () -> SettingsActivity.parseThreshold("1.01", Locale.US));
    }

    @Test
    public void unsavedKeySurvivesRotationAndSavedChangeReturnsResult() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(settingsIntent())) {
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.detection_location_cloud).performClick();
                input(activity, R.id.detection_api_key).setText("test-draft-key");
                input(activity, R.id.detection_model_id).setText("example/model");
                assertFalse(input(activity, R.id.detection_api_key).isSaveEnabled());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("test-draft-key", input(activity, R.id.detection_api_key).getText().toString());
                assertEquals("example/model", input(activity, R.id.detection_model_id).getText().toString());
                assertEquals("", AnalysisPreferences.openRouterApiKey(context));
                activity.findViewById(R.id.detection_save_cloud).performClick();
            });
            scenario.recreate();
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
            assertTrue(scenario.getResult().getResultData().getBooleanExtra(
                    SettingsActivity.EXTRA_ANALYSIS_CONFIGURATION_CHANGED, false));
            assertEquals("test-draft-key", AnalysisPreferences.openRouterApiKey(context));
        }
    }

    @Test
    public void invalidCloudConcurrencyDoesNotSaveAnyCloudSettings() {
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.OPENROUTER);
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(settingsIntent())) {
            scenario.onActivity(activity -> {
                input(activity, R.id.detection_api_key).setText("test-concurrency-key");
                input(activity, R.id.detection_model_id).setText("example/model");
                for (String invalid : new String[] {"0", "2.5", "", "2147483648"}) {
                    input(activity, R.id.detection_concurrency).setText(invalid);
                    activity.findViewById(R.id.detection_save_cloud).performClick();
                    assertNotNull(((TextInputLayout) activity.findViewById(
                            R.id.detection_concurrency_layout)).getError());
                    assertEquals(1, AnalysisPreferences.cloudConcurrency(context));
                    assertEquals("", AnalysisPreferences.openRouterApiKey(context));
                    assertEquals("", AnalysisPreferences.openRouterModel(context));
                }
            });
        }
    }

    @Test
    public void savedKeyWithMissingModelRequestsTheModelInsteadOfAnotherKey() {
        AnalysisPreferences.saveOpenRouterApiKey(context, "test-saved-key");
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.OPENROUTER);
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(settingsIntent())) {
            scenario.onActivity(activity -> {
                TextView status = activity.findViewById(R.id.detection_cloud_save_status);
                assertTrue(status.isShown());
                assertEquals(context.getString(R.string.detection_cloud_needs_model),
                        status.getText().toString());
                activity.findViewById(R.id.detection_mode_decisions).performClick();
                assertEquals(context.getString(R.string.detection_cloud_needs_model),
                        status.getText().toString());
                assertEquals("test-saved-key", AnalysisPreferences.openRouterApiKey(context));
            });
        }
    }

    @Test
    public void sectionPanesExposeTheirStaticContentAsSeparateReadingTargets() {
        AnalysisPreferences.saveOpenRouterApiKey(context, "test-saved-key");
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.DECISIONS);
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(settingsIntent())) {
            scenario.onActivity(activity -> {
                ViewGroup scroll = activity.findViewById(R.id.settings_scroll);
                View model = activity.findViewById(R.id.model_section);
                TextView status = activity.findViewById(R.id.detection_cloud_save_status);
                ArrayList<View> readingTargets = new ArrayList<>();
                // This is the platform's projection into the accessibility tree. An important
                // section wrapper becomes the target instead and TalkBack merges static text.
                scroll.addChildrenForAccessibility(readingTargets);
                assertFalse("The model pane must not merge all of its static descendants",
                        readingTargets.contains(model));
                assertTrue("Missing-model guidance must be its own reading target",
                        readingTargets.contains(status));
                assertTrue(status.isImportantForAccessibility());
                assertPaneEventTitle(model, context.getString(R.string.settings_section_model));

                activity.findViewById(R.id.section_allergens_button).performClick();
                readingTargets.clear();
                scroll.addChildrenForAccessibility(readingTargets);
                View allergens = activity.findViewById(R.id.allergen_section);
                assertFalse(readingTargets.contains(allergens));
                assertTrue("The allergen explanation must be independently readable",
                        readingTargets.stream().anyMatch(view -> view instanceof TextView
                                && context.getString(R.string.allergens_hint).contentEquals(
                                        ((TextView) view).getText())));
                assertPaneEventTitle(allergens,
                        context.getString(R.string.settings_section_allergens));
            });
        }
    }

    private static void assertPaneEventTitle(View pane, String title) {
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        try {
            // Removing the wrapper from reading order must retain its section-change semantics.
            pane.onPopulateAccessibilityEvent(event);
            assertEquals(title, pane.getAccessibilityPaneTitle());
            assertTrue(event.getText().contains(title));
        } finally {
            event.recycle();
        }
    }

    @Test
    public void localSaveIgnoresInvalidHiddenCloudConcurrencyAndKeepsItsSavedValue() {
        for (AnalysisPreferences.Mode local : new AnalysisPreferences.Mode[] {
                AnalysisPreferences.Mode.MATCHING, AnalysisPreferences.Mode.LOCAL_AI}) {
            AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.OPENROUTER);
            AnalysisPreferences.saveCloudConcurrency(context, 7);
            try (ActivityScenario<SettingsActivity> scenario =
                         ActivityScenario.launchActivityForResult(settingsIntent())) {
                scenario.onActivity(activity -> {
                    input(activity, R.id.detection_concurrency).setText("0");
                    activity.findViewById(R.id.detection_location_local).performClick();
                    if (local == AnalysisPreferences.Mode.LOCAL_AI) {
                        activity.findViewById(R.id.detection_mode_local_ai).performClick();
                    }
                    assertEquals(View.GONE, activity.findViewById(
                            R.id.detection_cloud_configuration).getVisibility());
                    // Exercise Save in the real unsaved-changes dialog, not a hidden save button.
                    activity.getOnBackPressedDispatcher().onBackPressed();
                    SettingsActivity.DiscardChangesDialog dialog =
                            (SettingsActivity.DiscardChangesDialog) activity.getSupportFragmentManager()
                                    .findFragmentByTag(SettingsActivity.DiscardChangesDialog.TAG);
                    assertNotNull(dialog);
                    assertTrue(dialog.requireDialog().findViewById(android.R.id.button1)
                            .performAccessibilityAction(
                                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
                });
                assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
                assertEquals(local, AnalysisPreferences.mode(context));
                assertEquals(7, AnalysisPreferences.cloudConcurrency(context));
            }
        }
    }

    @Test
    public void savingAllergensDoesNotClaimTheCloudConfigurationChanged() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launchActivityForResult(
                new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> activity.findViewById(R.id.save_preferences_button).performClick());
            assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
            assertFalse(scenario.getResult().getResultData().getBooleanExtra(
                    SettingsActivity.EXTRA_ANALYSIS_CONFIGURATION_CHANGED, true));
        }
    }

    @Test
    public void unchangedCloudSaveAndModeRoundTripDoNotReportAProviderChange() {
        for (boolean roundTrip : new boolean[] {false, true}) {
            savedCloudConfiguration();
            try (ActivityScenario<SettingsActivity> scenario =
                         ActivityScenario.launchActivityForResult(settingsIntent())) {
                scenario.onActivity(activity -> {
                    if (roundTrip) {
                        activity.findViewById(R.id.detection_location_local).performClick();
                        activity.findViewById(R.id.detection_location_cloud).performClick();
                    }
                    activity.findViewById(R.id.detection_save_cloud).performClick();
                    activity.getOnBackPressedDispatcher().onBackPressed();
                });
                Intent result = scenario.getResult().getResultData();
                assertFalse(result.getBooleanExtra(SettingsActivity.EXTRA_ANALYSIS_CONFIGURATION_CHANGED, true));
                assertFalse(result.getBooleanExtra(SettingsActivity.EXTRA_AI_PROVIDER_CHANGED, true));
            }
        }
    }

    @Test
    public void concurrencyChangeRequiresReanalysisWithoutChangingTheProviderIdentity() {
        savedCloudConfiguration();
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(settingsIntent())) {
            scenario.onActivity(activity -> {
                input(activity, R.id.detection_concurrency).setText("8");
                activity.findViewById(R.id.detection_save_cloud).performClick();
                activity.getOnBackPressedDispatcher().onBackPressed();
            });
            Intent result = scenario.getResult().getResultData();
            assertTrue(result.getBooleanExtra(SettingsActivity.EXTRA_ANALYSIS_CONFIGURATION_CHANGED, false));
            assertFalse(result.getBooleanExtra(SettingsActivity.EXTRA_AI_PROVIDER_CHANGED, true));
        }
    }

    @Test
    public void changedKeyOrActiveModelIsRecognizedAcrossRotation() {
        for (boolean key : new boolean[] {false, true}) {
            savedCloudConfiguration();
            try (ActivityScenario<SettingsActivity> scenario =
                         ActivityScenario.launchActivityForResult(settingsIntent())) {
                scenario.onActivity(activity -> {
                    input(activity, key ? R.id.detection_api_key : R.id.detection_model_id)
                            .setText(key ? "test-updated-key" : "example/updated-model");
                    activity.findViewById(R.id.detection_save_cloud).performClick();
                });
                scenario.recreate();
                scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
                Intent result = scenario.getResult().getResultData();
                assertTrue(result.getBooleanExtra(SettingsActivity.EXTRA_ANALYSIS_CONFIGURATION_CHANGED, false));
                assertTrue(result.getBooleanExtra(SettingsActivity.EXTRA_AI_PROVIDER_CHANGED, false));
            }
        }
    }

    private void savedCloudConfiguration() {
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.OPENROUTER);
        AnalysisPreferences.saveOpenRouterApiKey(context, "test-original-key");
        AnalysisPreferences.saveOpenRouterModel(context, "example/original-model");
        AnalysisPreferences.saveCloudConcurrency(context, 7);
    }

    @Test
    public void cloudConcurrencyDraftAndSavedValueSurviveRotation() {
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.OPENROUTER);
        AnalysisPreferences.saveOpenRouterApiKey(context, "test-concurrency-key");
        AnalysisPreferences.saveOpenRouterModel(context, "example/model");
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(settingsIntent())) {
            scenario.onActivity(activity -> {
                assertEquals("1", input(activity, R.id.detection_concurrency).getText().toString());
                input(activity, R.id.detection_concurrency).setText("16");
                assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
                assertEquals(1, AnalysisPreferences.cloudConcurrency(context));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("16", input(activity, R.id.detection_concurrency).getText().toString());
                assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
                activity.findViewById(R.id.detection_save_cloud).performClick();
                assertNull(((TextInputLayout) activity.findViewById(
                        R.id.detection_concurrency_layout)).getError());
                assertEquals(16, AnalysisPreferences.cloudConcurrency(context));
                assertFalse(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertEquals("16",
                    input(activity, R.id.detection_concurrency).getText().toString()));
        }
    }

    @Test
    public void concurrencyPreferencesRejectInvalidWritesAndRecoverFromCorruption() {
        assertEquals(1, AnalysisPreferences.cloudConcurrency(context));
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisPreferences.saveCloudConcurrency(context, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisPreferences.saveCloudConcurrency(context, -1));
        for (String corrupt : new String[] {"0", "-1", "2.5", "invalid", "2147483648"}) {
            assertTrue(context.getSharedPreferences("analysis_preferences", Context.MODE_PRIVATE)
                    .edit().putString("cloud_concurrency", corrupt).commit());
            assertEquals(1, AnalysisPreferences.cloudConcurrency(context));
        }
        assertTrue(context.getSharedPreferences("analysis_preferences", Context.MODE_PRIVATE)
                .edit().putInt("cloud_concurrency", 2).commit());
        assertEquals(1, AnalysisPreferences.cloudConcurrency(context));
        for (int valid : new int[] {1, 9, 16, Integer.MAX_VALUE}) {
            AnalysisPreferences.saveCloudConcurrency(context, valid);
            assertEquals(valid, AnalysisPreferences.cloudConcurrency(context));
            assertEquals(valid, SettingsActivity.parseConcurrency(Integer.toString(valid)));
        }
        assertThrows(NumberFormatException.class, () -> SettingsActivity.parseConcurrency("0"));
        assertThrows(NumberFormatException.class, () -> SettingsActivity.parseConcurrency("-1"));
        assertThrows(NumberFormatException.class, () -> SettingsActivity.parseConcurrency("2.5"));
        assertThrows(NumberFormatException.class, () -> SettingsActivity.parseConcurrency("2147483648"));
    }

    private static EditText input(SettingsActivity activity, int id) {
        return activity.findViewById(id);
    }
}
