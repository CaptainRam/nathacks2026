package com.example.allergycheck;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** The settings section switch, its entry points, the unsaved-draft guard and the results switch. */
@RunWith(AndroidJUnit4.class)
public class SettingsLayoutUiTest {
    private static final String MAY_CONTAIN_RED_WARNING = "may_contain_red_warning";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Set<String> originalAllergens;
    private boolean originalShowNotDetected;
    private boolean hadMayContainRedWarning;
    private boolean originalMayContainRedWarning;

    @Before
    public void clearAllergens() {
        originalAllergens = new HashSet<>(AllergenPreferences.selectedAllergenIds(
                context, AllergenDictionaryProvider.get(context)));
        AllergenPreferences.saveSelectedAllergenIds(context, Collections.emptySet());
        originalShowNotDetected = AllergenPreferences.showNotDetected(context);
        AllergenPreferences.saveShowNotDetected(context, false);
        SharedPreferences preferences = AllergenPreferences.preferences(context);
        hadMayContainRedWarning = preferences.contains(MAY_CONTAIN_RED_WARNING);
        originalMayContainRedWarning = AllergenPreferences.mayContainRedWarning(context);
        assertTrue(preferences.edit().remove(MAY_CONTAIN_RED_WARNING).commit());
    }

    @After
    public void restoreAllergens() {
        AllergenPreferences.saveSelectedAllergenIds(context, originalAllergens);
        AllergenPreferences.saveShowNotDetected(context, originalShowNotDetected);
        SharedPreferences.Editor editor = AllergenPreferences.preferences(context).edit();
        if (hadMayContainRedWarning) {
            editor.putBoolean(MAY_CONTAIN_RED_WARNING, originalMayContainRedWarning);
        } else {
            editor.remove(MAY_CONTAIN_RED_WARNING);
        }
        assertTrue(editor.commit());
    }

    @Test
    public void opensOnAllergensAndSwitchesToModelKeepingSaveBar() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                assertSection(activity, false);
                activity.findViewById(R.id.section_model_button).performClick();
                assertSection(activity, true);
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertSection(activity, true));
        }
    }

    @Test
    public void manageModelEntryOpensModelSection() {
        Intent intent = new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SHOW_MODEL, true);
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> assertSection(activity, true));
        }
    }

    @Test
    public void backIsGuardedOnlyWhileTheDraftDiffers() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                ViewGroup chips = activity.findViewById(R.id.allergen_options_container);
                assertTrue(chips.getChildCount() > 0);
                View chip = chips.getChildAt(0);
                assertFalse(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
                chip.performClick();
                assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
                chip.performClick();
                assertFalse(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
            });
        }
    }

    @Test
    public void keepEditingLeavesOneDialogThatSurvivesRotation() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                editDraftAndPressBack(activity);
                // A second back request while the question is up must not stack another one.
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(1, activity.getSupportFragmentManager().getFragments().size());
            });
            scenario.recreate();
            scenario.onActivity(activity -> click(activity, DialogInterface.BUTTON_NEUTRAL));
            // Dialog button handlers and the dismissal run on a later main-thread turn.
            scenario.onActivity(activity -> {
                activity.getSupportFragmentManager().executePendingTransactions();
                assertNull(discardDialog(activity));
                assertFalse(activity.isFinishing());
            });
        }
    }

    @Test
    public void discardLeavesWithoutSaving() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                editDraftAndPressBack(activity);
                click(activity, DialogInterface.BUTTON_NEGATIVE);
            });
            // Blocks until the activity finishes, which the posted handler does.
            assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
        }
        assertTrue(AllergenPreferences.selectedAllergenIds(
                context, AllergenDictionaryProvider.get(context)).isEmpty());
    }

    @Test
    public void saveFromDialogStoresDraftAndReturnsOk() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                editDraftAndPressBack(activity);
                click(activity, DialogInterface.BUTTON_POSITIVE);
            });
            assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
        }
        String firstId = AllergenDictionaryProvider.get(context).allergens().iterator().next().id();
        assertEquals(Collections.singleton(firstId), AllergenPreferences.selectedAllergenIds(
                context, AllergenDictionaryProvider.get(context)));
    }

    @Test
    public void showNotDetectedIsOffByDefault() {
        context.getSharedPreferences("allergy_preferences", Context.MODE_PRIVATE).edit()
                .remove("show_not_detected").commit();
        assertFalse(AllergenPreferences.showNotDetected(context));
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> assertFalse(notDetectedSwitch(activity).isChecked()));
        }
    }

    @Test
    public void showNotDetectedSwitchIsPartOfTheDraft() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                MaterialSwitch toggle = notDetectedSwitch(activity);
                assertFalse(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
                // Tapping the row, not only the switch, flips it.
                ((View) toggle.getParent()).performClick();
                assertTrue(toggle.isChecked());
                assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
                toggle.performClick();
                assertFalse(toggle.isChecked());
                assertFalse(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
                toggle.performClick();
            });
            // The unsaved draft survives rotation and is still not stored.
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(notDetectedSwitch(activity).isChecked());
                assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
            });
        }
        assertFalse(AllergenPreferences.showNotDetected(context));
    }

    @Test
    public void saveStoresShowNotDetected() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                notDetectedSwitch(activity).performClick();
                activity.findViewById(R.id.save_preferences_button).performClick();
            });
            assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
        }
        assertTrue(AllergenPreferences.showNotDetected(context));
    }

    @Test
    public void allergenDraftIsSavedFromTheModelSection() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                ViewGroup chips = activity.findViewById(R.id.allergen_options_container);
                chips.getChildAt(0).performClick();
                activity.findViewById(R.id.section_model_button).performClick();
                assertSection(activity, true);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.model_save_note).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.selection_summary).getVisibility());
                activity.findViewById(R.id.save_preferences_button).performClick();
            });
            assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
        }
        String firstId = AllergenDictionaryProvider.get(context).allergens().iterator().next().id();
        assertEquals(Collections.singleton(firstId), AllergenPreferences.selectedAllergenIds(
                context, AllergenDictionaryProvider.get(context)));
    }

    @Test
    public void saveWithoutChangesLeavesWithoutAResult() {
        Intent intent = new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SHOW_MODEL, true);
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(intent)) {
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.save_preferences_button).performClick());
            // RESULT_OK would make the caller re-check the label, for nothing.
            assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
        }
    }

    @Test
    public void switchingSectionsLeavesTheLiveCountTextAlone() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                TextView summary = activity.findViewById(R.id.selection_summary);
                View note = activity.findViewById(R.id.model_save_note);
                // Only the count announces itself; the tab-dependent note never does.
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, summary.getAccessibilityLiveRegion());
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, note.getAccessibilityLiveRegion());
                CharSequence before = summary.getText().toString();
                activity.findViewById(R.id.section_model_button).performClick();
                assertEquals(before, summary.getText().toString());
                // No pending allergen draft, so the note stays out of sight too.
                assertEquals(View.INVISIBLE, note.getVisibility());
                activity.findViewById(R.id.section_allergens_button).performClick();
                assertEquals(before, summary.getText().toString());
                assertEquals(View.VISIBLE, summary.getVisibility());
            });
        }
    }

    @Test
    public void discardKeepsShowNotDetectedOff() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                notDetectedSwitch(activity).performClick();
                activity.getOnBackPressedDispatcher().onBackPressed();
                click(activity, DialogInterface.BUTTON_NEGATIVE);
            });
            assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
        }
        assertFalse(AllergenPreferences.showNotDetected(context));
    }

    @Test
    public void mayContainRedWarningIsOnByDefault() {
        assertFalse(AllergenPreferences.preferences(context).contains(MAY_CONTAIN_RED_WARNING));
        assertTrue(AllergenPreferences.mayContainRedWarning(context));
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> assertTrue(mayContainRedWarningSwitch(activity).isChecked()));
        }
    }

    @Test
    public void mayContainRedWarningSwitchIsPartOfTheDraft() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                MaterialSwitch toggle = mayContainRedWarningSwitch(activity);
                assertFalse(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
                ((View) toggle.getParent()).performClick();
                assertFalse(toggle.isChecked());
                assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
                toggle.performClick();
                assertTrue(toggle.isChecked());
                assertFalse(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
                toggle.performClick();
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertFalse(mayContainRedWarningSwitch(activity).isChecked());
                assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
            });
        }
        assertTrue(AllergenPreferences.mayContainRedWarning(context));
        assertFalse(AllergenPreferences.preferences(context).contains(MAY_CONTAIN_RED_WARNING));
    }

    @Test
    public void saveStoresMayContainRedWarningAndRestoresItWhenReopened() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                mayContainRedWarningSwitch(activity).performClick();
                activity.findViewById(R.id.save_preferences_button).performClick();
            });
            assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
        }
        assertFalse(AllergenPreferences.mayContainRedWarning(context));
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                assertFalse(mayContainRedWarningSwitch(activity).isChecked());
                assertFalse(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
            });
        }
    }

    @Test
    public void discardKeepsMayContainRedWarningOn() {
        try (ActivityScenario<SettingsActivity> scenario =
                     ActivityScenario.launchActivityForResult(new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                mayContainRedWarningSwitch(activity).performClick();
                activity.getOnBackPressedDispatcher().onBackPressed();
                click(activity, DialogInterface.BUTTON_NEGATIVE);
            });
            assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
        }
        assertTrue(AllergenPreferences.mayContainRedWarning(context));
        assertFalse(AllergenPreferences.preferences(context).contains(MAY_CONTAIN_RED_WARNING));
    }

    private static MaterialSwitch notDetectedSwitch(SettingsActivity activity) {
        return activity.findViewById(R.id.show_not_detected_switch);
    }

    private static MaterialSwitch mayContainRedWarningSwitch(SettingsActivity activity) {
        return activity.findViewById(R.id.may_contain_red_warning_switch);
    }

    /** Checks the first allergen chip, then asks to leave, which must raise the question. */
    private static void editDraftAndPressBack(SettingsActivity activity) {
        ViewGroup chips = activity.findViewById(R.id.allergen_options_container);
        chips.getChildAt(0).performClick();
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertNotNull(discardDialog(activity));
        assertFalse(activity.isFinishing());
    }

    private static DialogFragment discardDialog(SettingsActivity activity) {
        return (DialogFragment) activity.getSupportFragmentManager()
                .findFragmentByTag(SettingsActivity.DiscardChangesDialog.TAG);
    }

    private static void click(SettingsActivity activity, int which) {
        DialogFragment fragment = discardDialog(activity);
        assertNotNull(fragment);
        AlertDialog dialog = (AlertDialog) fragment.requireDialog();
        assertTrue(dialog.getButton(which).performClick());
    }

    private static void assertSection(SettingsActivity activity, boolean model) {
        assertEquals(model ? View.GONE : View.VISIBLE,
                activity.findViewById(R.id.allergen_section).getVisibility());
        assertEquals(model ? View.VISIBLE : View.GONE,
                activity.findViewById(R.id.model_section).getVisibility());
        // One page-wide Save serves both sections.
        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_save_bar).getVisibility());
    }
}
