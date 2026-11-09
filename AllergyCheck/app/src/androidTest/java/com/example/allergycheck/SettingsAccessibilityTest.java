package com.example.allergycheck;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.google.android.material.chip.Chip;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Checks the semantics consumed by TalkBack, including actions and checked/expanded state. */
@RunWith(AndroidJUnit4.class)
public class SettingsAccessibilityTest {
    private Context context;
    private final Map<String, Map<String, ?>> originalPreferences = new HashMap<>();

    @Before
    public void isolatePreferences() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[] {"allergy_preferences", "analysis_preferences"}) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            originalPreferences.put(name, new HashMap<>(preferences.getAll()));
            assertTrue(preferences.edit().clear().commit());
        }
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
    }

    @After
    @SuppressWarnings("unchecked")
    public void restorePreferences() {
        for (Map.Entry<String, Map<String, ?>> file : originalPreferences.entrySet()) {
            SharedPreferences.Editor editor = context.getSharedPreferences(
                    file.getKey(), Context.MODE_PRIVATE).edit().clear();
            for (Map.Entry<String, ?> entry : file.getValue().entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Set) editor.putStringSet(entry.getKey(),
                        new HashSet<>((Set<String>) value));
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                else editor.putString(entry.getKey(), (String) value);
            }
            assertTrue(editor.commit());
        }
    }

    @Test
    public void resultsRowExposesOneLabeledNativeSwitchWithWorkingAccessibilityAction() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                MaterialSwitch toggle = activity.findViewById(R.id.show_not_detected_switch);
                ViewGroup row = (ViewGroup) toggle.getParent();
                assertFalse(row.isImportantForAccessibility());
                ArrayList<View> accessibleChildren = new ArrayList<>();
                row.addChildrenForAccessibility(accessibleChildren);
                assertEquals(1, accessibleChildren.size());
                assertSame(toggle, accessibleChildren.get(0));

                AccessibilityNodeInfo node = toggle.createAccessibilityNodeInfo();
                assertEquals("android.widget.Switch", node.getClassName().toString());
                assertEquals(activity.getString(R.string.a11y_show_not_detected),
                        node.getContentDescription().toString());
                assertTrue(node.isCheckable());
                assertFalse(node.isChecked());
                assertTrue(toggle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertTrue(toggle.createAccessibilityNodeInfo().isChecked());
                assertTrue(toggle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertFalse(toggle.createAccessibilityNodeInfo().isChecked());
                // This control edits a draft; accessibility actions must retain that behavior.
                assertFalse(AllergenPreferences.showNotDetected(context));
            });
        }
    }

    @Test
    public void modelDetailsAnnouncesExpandedStateAndRespondsToAccessibilityClick() {
        Intent intent = new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SHOW_MODEL, true);
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                View button = activity.findViewById(R.id.model_details_button);
                View details = activity.findViewById(R.id.model_details);
                assertEquals(activity.getString(R.string.a11y_model_details),
                        button.createAccessibilityNodeInfo().getContentDescription().toString());
                assertEquals(activity.getString(R.string.a11y_settings_collapsed),
                        button.createAccessibilityNodeInfo().getStateDescription().toString());
                assertEquals(View.GONE, details.getVisibility());
                assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertEquals(View.VISIBLE, details.getVisibility());
                assertEquals(activity.getString(R.string.a11y_settings_expanded),
                        button.createAccessibilityNodeInfo().getStateDescription().toString());
                assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertEquals(View.GONE, details.getVisibility());
                assertEquals(activity.getString(R.string.a11y_settings_collapsed),
                        button.createAccessibilityNodeInfo().getStateDescription().toString());
            });
        }
    }

    @Test
    public void allergenOptionsExposeTheirLabelsAndNativeCheckStateWith48DpTargets() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                ViewGroup options = activity.findViewById(R.id.allergen_options_container);
                List<Allergen> allergens = AllergenDictionaryProvider.get(context).allergens();
                assertEquals(allergens.size(), options.getChildCount());
                assertTrue(options.getChildCount() > 0);
                int minimumTarget = Math.round(48 * activity.getResources().getDisplayMetrics().density);
                int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                for (int index = 0; index < options.getChildCount(); index++) {
                    Chip chip = (Chip) options.getChildAt(index);
                    AccessibilityNodeInfo node = chip.createAccessibilityNodeInfo();
                    assertEquals(allergens.get(index).label(), node.getText().toString());
                    assertTrue(node.isCheckable());
                    assertFalse(node.isChecked());
                    chip.measure(unspecified, unspecified);
                    assertTrue(chip.getMeasuredWidth() >= minimumTarget);
                    assertTrue(chip.getMeasuredHeight() >= minimumTarget);
                }
                Chip first = (Chip) options.getChildAt(0);
                assertTrue(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertTrue(first.createAccessibilityNodeInfo().isChecked());
            });
        }
    }
}
