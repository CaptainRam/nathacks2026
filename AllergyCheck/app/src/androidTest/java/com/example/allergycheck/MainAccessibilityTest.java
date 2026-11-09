package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Accessibility actions and semantic state through photo/loading/cancellation transitions. */
@RunWith(AndroidJUnit4.class)
public class MainAccessibilityTest {
    private Context context;
    private final Map<String, Map<String, ?>> previous = new HashMap<>();

    @Before
    public void isolatePreferences() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[] {"allergy_preferences", "analysis_preferences"}) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            previous.put(name, new HashMap<>(preferences.getAll()));
        }
        assertTrue(AllergenPreferences.preferences(context).edit()
                .remove("selected_photo").putStringSet("selected_allergens",
                        Collections.singleton("MILK")).commit());
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
    }

    @After
    @SuppressWarnings("unchecked")
    public void restorePreferences() {
        for (Map.Entry<String, Map<String, ?>> file : previous.entrySet()) {
            SharedPreferences.Editor editor = context.getSharedPreferences(file.getKey(),
                    Context.MODE_PRIVATE).edit().clear();
            for (Map.Entry<String, ?> entry : file.getValue().entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) editor.putString(entry.getKey(), (String) value);
                else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof Set) editor.putStringSet(entry.getKey(),
                        new HashSet<>((Set<String>) value));
            }
            assertTrue(editor.commit());
        }
    }

    @Test
    public void loadingExposesOneNamedStatusAndHidesCoveredPhotoControls() {
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            scan.onActivity(activity -> {
                View allergens = activity.findViewById(R.id.allergens_button);
                assertTrue(allergens.createAccessibilityNodeInfo().getContentDescription()
                        .toString().contains("Milk"));
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                        activity.findViewById(R.id.allergen_chips).getImportantForAccessibility());

                ScanTestSupport.invoke(activity, "setLoading", new Class<?>[] {boolean.class}, true);
                View loading = activity.findViewById(R.id.loading_overlay);
                AccessibilityNodeInfo node = loading.createAccessibilityNodeInfo();
                assertEquals(context.getString(R.string.loading_photo), node.getContentDescription());
                assertTrue(node.isScreenReaderFocusable());
                assertFalse(node.isClickable());
                assertFalse(loading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                        activity.findViewById(R.id.preview_state).getImportantForAccessibility());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                        activity.findViewById(R.id.empty_state).getImportantForAccessibility());
                assertFalse(activity.findViewById(R.id.remove_button).isEnabled());

                ScanTestSupport.invoke(activity, "setLoading", new Class<?>[] {boolean.class}, false);
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                        activity.findViewById(R.id.preview_state).getImportantForAccessibility());
                assertTrue(activity.findViewById(R.id.import_button).isEnabled());
                assertEquals(context.getString(R.string.tab_scan),
                        activity.findViewById(R.id.content_scroll).getAccessibilityPaneTitle());
            });
        }
    }

    @Test
    public void talkBackCanCancelACheckAndRemoveThePhoto() throws Exception {
        try (ScanTestSupport support = new ScanTestSupport(context);
             ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            File photo = support.createBlankPhoto();
            support.loadPhoto(scan, photo);
            ScanTestSupport.awaitPhotoReady(scan);
            scan.onActivity(activity -> {
                View pane = activity.findViewById(R.id.content_scroll);
                assertEquals(context.getString(R.string.photo_added), pane.getAccessibilityPaneTitle());
                assertTrue(activity.findViewById(R.id.start_analysis_button)
                        .performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertEquals(null, pane.getAccessibilityPaneTitle());
                assertEquals(context.getString(R.string.scan_in_progress), activity.findViewById(
                        R.id.analysis_loading_title).getAccessibilityPaneTitle());
                View cancel = activity.findViewById(R.id.cancel_analysis_button);
                assertEquals(context.getString(R.string.cancel_ingredient_check),
                        cancel.createAccessibilityNodeInfo().getContentDescription());
                assertTrue(cancel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertEquals(context.getString(R.string.photo_added), pane.getAccessibilityPaneTitle());
                assertEquals(View.GONE, activity.findViewById(R.id.analysis_group).getVisibility());

                View remove = activity.findViewById(R.id.remove_button);
                float minPixels = 48 * activity.getResources().getDisplayMetrics().density;
                assertTrue(remove.getLayoutParams().width >= minPixels);
                assertTrue(remove.getLayoutParams().height >= minPixels);
                assertTrue(remove.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                assertEquals(context.getString(R.string.tab_scan), pane.getAccessibilityPaneTitle());
                assertEquals(View.GONE, activity.findViewById(R.id.preview_state).getVisibility());
            });
        }
    }
}
