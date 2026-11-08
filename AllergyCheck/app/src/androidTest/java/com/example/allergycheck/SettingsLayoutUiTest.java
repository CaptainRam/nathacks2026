package com.example.allergycheck;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The settings section switch, its entry points, and the unsaved-draft guard. Saves nothing. */
@RunWith(AndroidJUnit4.class)
public class SettingsLayoutUiTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void opensOnAllergensAndSwitchesToModelWithoutSaveBar() {
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

    private static void assertSection(SettingsActivity activity, boolean model) {
        assertEquals(model ? View.GONE : View.VISIBLE,
                activity.findViewById(R.id.allergen_section).getVisibility());
        assertEquals(model ? View.VISIBLE : View.GONE,
                activity.findViewById(R.id.model_section).getVisibility());
        assertEquals(model ? View.GONE : View.VISIBLE,
                activity.findViewById(R.id.settings_save_bar).getVisibility());
    }
}
