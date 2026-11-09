package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.widget.NestedScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.ocr.FrameReadability;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Camera semantics and layout without requiring a camera or changing device preferences. */
@RunWith(AndroidJUnit4.class)
public class CameraAccessibilityTest {
    @Test
    public void togglesKeepTheirNamesAndExposeNativeCheckedState() {
        withAttachedCamera(root -> {
            MaterialButton auto = root.findViewById(R.id.capture_auto_button);
            CameraCaptureActivity.describeCameraToggle(auto, R.string.camera_auto_capture,
                    R.string.camera_auto_capture_off, false);
            AccessibilityNodeInfo off = auto.createAccessibilityNodeInfo();
            assertEquals(root.getResources().getString(R.string.camera_auto_capture),
                    off.getContentDescription());
            assertTrue(off.isCheckable());
            assertFalse(off.isChecked());
            CameraCaptureActivity.describeCameraToggle(auto, R.string.camera_auto_capture,
                    R.string.camera_auto_capture_on, true);
            AccessibilityNodeInfo on = auto.createAccessibilityNodeInfo();
            assertEquals(off.getContentDescription(), on.getContentDescription());
            assertTrue(on.isChecked());
            assertEquals(root.getResources().getString(R.string.camera_auto_capture_on),
                    auto.getTooltipText());

            MaterialButton torch = root.findViewById(R.id.capture_torch_button);
            torch.setVisibility(View.VISIBLE);
            CameraCaptureActivity.describeCameraToggle(torch, R.string.camera_flashlight,
                    R.string.camera_flashlight_on, true);
            assertTrue(torch.createAccessibilityNodeInfo().isChecked());
            assertEquals(root.getResources().getString(R.string.camera_flashlight),
                    torch.getContentDescription());
        });
    }

    @Test
    public void statusIsPoliteAndManualCaptureExposesSavingState() {
        withAttachedCamera(root -> {
            View status = root.findViewById(R.id.capture_status);
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.getAccessibilityLiveRegion());
            assertTrue(status.isScreenReaderFocusable());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    root.findViewById(R.id.camera_preview).getImportantForAccessibility());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                    root.findViewById(R.id.guide_overlay).getImportantForAccessibility());
            CaptureFeedback feedback = feedback(root);
            feedback.beginCapture();
            TextView label = root.findViewById(R.id.capture_status_text);
            String saving = root.getResources().getString(R.string.a11y_camera_saving_photo);
            assertEquals(saving, label.getText().toString());
            View shutter = root.findViewById(R.id.capture_button);
            assertFalse(shutter.isEnabled());
            assertEquals(saving, shutter.createAccessibilityNodeInfo().getStateDescription());
            assertEquals(root.getResources().getString(R.string.take_photo),
                    shutter.getContentDescription());
        });
    }

    @Test
    public void enablingManualCaptureDoesNotRepeatAnUnchangedFramingInstruction() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = cameraLayout(1f, 360, 640);
            CaptureFeedback feedback = feedback(root);
            AtomicInteger textChanges = new AtomicInteger();
            ((TextView) root.findViewById(R.id.capture_status_text)).addTextChangedListener(
                    new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int start, int count,
                                int after) { }
                        @Override public void onTextChanged(CharSequence s, int start, int before,
                                int count) { textChanges.incrementAndGet(); }
                        @Override public void afterTextChanged(Editable s) { }
                    });
            feedback.start();
            try {
                // Let the existing manual override deadline elapse without a 30-second test wait.
                Method apply = CaptureFeedback.class.getDeclaredMethod("apply",
                        FrameReadability.Verdict.class, long.class, boolean.class);
                apply.setAccessible(true);
                apply.invoke(feedback, FrameReadability.Verdict.SEARCHING,
                        SystemClock.uptimeMillis() + 31_000L, false);
                assertTrue(root.findViewById(R.id.capture_button).isEnabled());
                assertEquals(0, textChanges.get());
                assertEquals(root.getResources().getString(R.string.camera_state_searching),
                        ((TextView) root.findViewById(R.id.capture_status_text)).getText().toString());
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            } finally {
                feedback.freeze();
            }
        });
    }

    @Test
    public void enlargedTextInAShortWindowCanScrollToTheWholeShutter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = cameraLayout(2f, 640, 288);
            measure(root, 640, 288);
            CameraCaptureActivity.fitPreviewToViewport(root);
            measure(root, 640, 288);
            NestedScrollView scroll = root.findViewById(R.id.camera_scroll);
            assertTrue(scroll.canScrollVertically(1));
            scroll.setSmoothScrollingEnabled(false);
            scroll.fullScroll(View.FOCUS_DOWN);
            TextView shutter = root.findViewById(R.id.capture_button);
            Rect bounds = new Rect();
            shutter.getDrawingRect(bounds);
            scroll.offsetDescendantRectToMyCoords(shutter, bounds);
            assertTrue(bounds.top >= scroll.getScrollY());
            assertTrue(bounds.bottom <= scroll.getScrollY() + scroll.getHeight());
            assertTrue(shutter.getLayout().getLineCount() > 0);
            for (int line = 0; line < shutter.getLayout().getLineCount(); line++) {
                assertEquals(0, shutter.getLayout().getEllipsisCount(line));
            }
        });
    }

    private void withAttachedCamera(Consumer<View> assertion) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(target)) {
            scan.onActivity(activity -> {
                View root = cameraLayout(1f, 360, 640);
                ViewGroup content = activity.findViewById(android.R.id.content);
                content.addView(root, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                try {
                    measure(root, 360, 640);
                    // Node info is populated only for a real attached hierarchy on recent Android.
                    // Keep the native node assertions, using the same attachment as a camera screen.
                    assertTrue(root.isAttachedToWindow());
                    assertion.accept(root);
                } finally {
                    content.removeView(root);
                }
            });
        }
    }

    private CaptureFeedback feedback(View root) {
        return new CaptureFeedback(root.findViewById(R.id.guide_overlay),
                root.findViewById(R.id.capture_status),
                (ImageView) root.findViewById(R.id.capture_status_icon),
                (MaterialTextView) root.findViewById(R.id.capture_status_text),
                root.findViewById(R.id.capture_button), () -> { });
    }

    private View cameraLayout(float scale, int widthDp, int heightDp) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config = new Configuration(target.getResources().getConfiguration());
        config.fontScale = scale;
        config.screenWidthDp = widthDp;
        config.screenHeightDp = heightDp;
        Context themed = new ContextThemeWrapper(target.createConfigurationContext(config),
                R.style.Theme_AllergyCheck);
        return LayoutInflater.from(themed).inflate(R.layout.activity_camera_capture, null);
    }

    private void measure(View root, int widthDp, int heightDp) {
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density);
        int height = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
}
