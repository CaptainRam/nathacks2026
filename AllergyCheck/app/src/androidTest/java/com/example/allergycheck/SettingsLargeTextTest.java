package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.widget.NestedScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Render the real settings layouts at narrow widths with enlarged text, without global settings. */
@RunWith(AndroidJUnit4.class)
public class SettingsLargeTextTest {
    @Test
    public void largeTextKeepsEverySegmentLabelVisibleAndBothGroupsSelectable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = largeTextContext();
            View root = LayoutInflater.from(context).inflate(R.layout.activity_settings, null);
            root.findViewById(R.id.allergen_section).setVisibility(View.GONE);
            root.findViewById(R.id.model_section).setVisibility(View.VISIBLE);
            root.findViewById(R.id.settings_save_bar).setVisibility(View.GONE);
            measureNarrowScreen(root);

            assertGroup(root.findViewById(R.id.settings_section_toggle));
            assertGroup(root.findViewById(R.id.detection_location));
            TextView availability = root.findViewById(R.id.model_availability);
            assertFullyRendered(availability);
        });
    }

    @Test
    public void shortWindowLeavesUsableContentAndCanScrollToTheSaveAction() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = settingsAtSize(640, 288);
            NestedScrollView scroll = root.findViewById(R.id.settings_scroll);
            float density = root.getResources().getDisplayMetrics().density;
            assertTrue("Fixed bars must leave at least one accessible touch target of content",
                    scroll.getHeight() >= Math.round(48 * density));
            assertTrue("Allergen content remains scrollable", scroll.canScrollVertically(1));

            scroll.setSmoothScrollingEnabled(false);
            assertTrue(scroll.fullScroll(View.FOCUS_DOWN));
            TextView save = root.findViewById(R.id.save_preferences_button);
            assertVisibleInScroll(scroll, save);
            assertFullyRendered(save);
            assertGroup(root.findViewById(R.id.settings_section_toggle));
        });
    }

    @Test
    public void shortModelWindowRetainsThePageWideSaveAndPendingAllergenNote() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = settingsAtSize(640, 288, true);
            NestedScrollView scroll = root.findViewById(R.id.settings_scroll);
            View bar = root.findViewById(R.id.settings_save_bar);
            assertEquals(root.findViewById(R.id.settings_scroll_content), bar.getParent());
            assertEquals(View.VISIBLE, bar.getVisibility());
            scroll.setSmoothScrollingEnabled(false);
            assertTrue(scroll.fullScroll(View.FOCUS_DOWN));
            TextView save = root.findViewById(R.id.save_preferences_button);
            TextView note = root.findViewById(R.id.model_save_note);
            assertVisibleInScroll(scroll, save);
            assertFullyRendered(save);
            assertFullyRendered(note);
        });
    }

    @Test
    public void narrowShortWindowCanBringSaveFullyIntoViewWithThePendingDraftNote() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = settingsAtSize(288, 360, true);
            NestedScrollView scroll = root.findViewById(R.id.settings_scroll);
            scroll.setSmoothScrollingEnabled(false);
            TextView save = root.findViewById(R.id.save_preferences_button);
            Rect rectangle = new Rect();
            save.getDrawingRect(rectangle);
            save.requestRectangleOnScreen(rectangle, true);
            assertVisibleInScroll(scroll, save);
            assertFullyRendered(save);
            assertFullyRendered(root.findViewById(R.id.model_save_note));
        });
    }

    @Test
    public void tallerWindowKeepsSaveVisibleWhileAllergenContentScrolls() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = settingsAtSize(288, 640);
            NestedScrollView scroll = root.findViewById(R.id.settings_scroll);
            TextView save = root.findViewById(R.id.save_preferences_button);
            Rect before = new Rect();
            save.getDrawingRect(before);
            ((android.view.ViewGroup) root).offsetDescendantRectToMyCoords(save, before);

            scroll.setSmoothScrollingEnabled(false);
            assertTrue(scroll.fullScroll(View.FOCUS_DOWN));
            Rect after = new Rect();
            save.getDrawingRect(after);
            ((android.view.ViewGroup) root).offsetDescendantRectToMyCoords(save, after);
            assertEquals("Normal-height windows retain the pinned save action", before, after);
            assertTrue(after.top >= 0 && after.bottom <= root.getHeight());
        });
    }

    private View settingsAtSize(int widthDp, int heightDp) {
        return settingsAtSize(widthDp, heightDp, false);
    }

    private View settingsAtSize(int widthDp, int heightDp, boolean model) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(target.getResources().getConfiguration());
        config.fontScale = 2f;
        config.screenWidthDp = widthDp;
        config.screenHeightDp = heightDp;
        Context context = new ContextThemeWrapper(target.createConfigurationContext(config),
                R.style.Theme_AllergyCheck);
        View root = LayoutInflater.from(context).inflate(R.layout.activity_settings, null);
        if (model) {
            root.findViewById(R.id.allergen_section).setVisibility(View.GONE);
            root.findViewById(R.id.model_section).setVisibility(View.VISIBLE);
            root.findViewById(R.id.selection_summary).setVisibility(View.GONE);
            root.findViewById(R.id.model_save_note).setVisibility(View.VISIBLE);
        }
        SettingsActivity.arrangeSaveBar(root);
        float density = root.getResources().getDisplayMetrics().density;
        // Account for system bars as the real activity's insets listener does.
        int inset = Math.round(24 * density);
        root.setPadding(0, inset, 0, inset);
        int width = Math.round(widthDp * density);
        int height = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        return root;
    }

    private void assertVisibleInScroll(NestedScrollView scroll, View child) {
        Rect bounds = new Rect();
        child.getDrawingRect(bounds);
        scroll.offsetDescendantRectToMyCoords(child, bounds);
        assertTrue("Save action can scroll fully into the viewport",
                bounds.top >= scroll.getScrollY() + scroll.getPaddingTop()
                        && bounds.bottom <= scroll.getScrollY() + scroll.getHeight()
                                - scroll.getPaddingBottom());
    }

    private Context largeTextContext() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(target.getResources().getConfiguration());
        config.fontScale = 2f;
        return new ContextThemeWrapper(target.createConfigurationContext(config),
                R.style.Theme_AllergyCheck);
    }

    private void measureNarrowScreen(View root) {
        float density = root.getResources().getDisplayMetrics().density;
        // 1080px at density 600 is 288dp, the display size that exposed clipped labels.
        int width = Math.round(288 * density);
        int height = Math.round(640 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private void assertGroup(MaterialButtonToggleGroup group) {
        MaterialButton first = (MaterialButton) group.getChildAt(0);
        MaterialButton second = (MaterialButton) group.getChildAt(1);
        assertFullyRendered(first);
        assertFullyRendered(second);
        assertEquals("Segments retain a continuous equal-height outline", first.getHeight(),
                second.getHeight());
        assertTrue(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertTrue(first.createAccessibilityNodeInfo().isChecked());
        assertFalse(second.createAccessibilityNodeInfo().isChecked());
        assertTrue(second.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(first.createAccessibilityNodeInfo().isChecked());
        assertTrue(second.createAccessibilityNodeInfo().isChecked());
    }

    private void assertFullyRendered(TextView label) {
        Layout text = label.getLayout();
        assertNotNull(text);
        assertTrue("Label must have available width", text.getWidth() > 0);
        assertTrue("All lines must fit inside the control",
                text.getHeight() <= label.getHeight()
                        - label.getCompoundPaddingTop() - label.getCompoundPaddingBottom());
        for (int line = 0; line < text.getLineCount(); line++) {
            assertEquals("No option name may be abbreviated", 0, text.getEllipsisCount(line));
        }
        assertEquals("All characters must remain visible", label.getText().length(),
                text.getLineEnd(text.getLineCount() - 1));
    }
}
