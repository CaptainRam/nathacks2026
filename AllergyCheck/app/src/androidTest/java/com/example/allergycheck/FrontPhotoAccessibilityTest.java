package com.example.allergycheck;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.crosscheck.FoodTypeEstimate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Framework accessibility nodes and async front-photo transitions; no cloud requests. */
@RunWith(AndroidJUnit4.class)
public class FrontPhotoAccessibilityTest {
    private Context context;
    private ScanTestSupport support;
    private UiAutomation automation;
    private AccessibilityServiceInfo originalService;
    private final Map<String, Map<String, ?>> previous = new HashMap<>();

    @Before
    public void isolateConfigurationAndEnableComponentFocus() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        originalService = automation.getServiceInfo();
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        automation.setServiceInfo(service);
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!manager.isTouchExplorationEnabled() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25);
        }
        assertTrue(manager.isTouchExplorationEnabled());
        for (String name : new String[] {"allergy_preferences", "analysis_preferences", "analysis_credentials"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            previous.put(name, new HashMap<>(prefs.getAll()));
            assertTrue(prefs.edit().clear().commit());
        }
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
        AllergenPreferences.saveSelectedAllergenIds(context, Collections.singleton("MILK"));
        support = new ScanTestSupport(context);
    }

    @After
    @SuppressWarnings("unchecked")
    public void restoreConfiguration() {
        if (automation != null && originalService != null) automation.setServiceInfo(originalService);
        if (support != null) support.close();
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
    public void oneFrontPhotoButtonRetainsReadingPositionAcrossLoadAndAccessibleRemoval() throws Exception {
        File front = support.createLabel("FRONT PHOTO");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            support.loadPhoto(scan, support.createLabel("INGREDIENTS: MILK, SUGAR."));
            awaitLabelPhotoReady(scan);
            AtomicReference<View> target = new AtomicReference<>();
            scan.onActivity(activity -> {
                View card = activity.findViewById(R.id.front_photo_card);
                target.set(card);
                AccessibilityNodeInfo empty = card.createAccessibilityNodeInfo();
                assertEquals("android.widget.Button", empty.getClassName());
                assertEquals(context.getString(R.string.crosscheck_photo_accessibility_label),
                        empty.getContentDescription());
                assertEquals(context.getString(R.string.crosscheck_photo_empty), empty.getStateDescription());
                assertEquals(0, empty.getChildCount());
                assertAction(empty, AccessibilityNodeInfo.ACTION_CLICK, R.string.crosscheck_add_photo);
                assertTrue(card.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
                controller(activity).loadPhoto(support.photoUri(front));
            });
            await(scan, activity -> !controller(activity).isPhotoLoading()
                    && controller(activity).currentBitmap() != null);
            scan.onActivity(activity -> {
                View card = activity.findViewById(R.id.front_photo_card);
                assertSame(target.get(), card);
                assertTrue(card.isAccessibilityFocused());
                AccessibilityNodeInfo loaded = card.createAccessibilityNodeInfo();
                assertEquals(0, loaded.getChildCount());
                assertEquals(context.getString(R.string.crosscheck_photo_added), loaded.getStateDescription());
                assertAction(loaded, AccessibilityNodeInfo.ACTION_CLICK, R.string.crosscheck_change_photo);
                assertAction(loaded, AccessibilityNodeInfo.ACTION_DISMISS, R.string.crosscheck_remove_photo);
                assertTrue(card.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null));
                assertSame(target.get(), activity.findViewById(R.id.front_photo_card));
                assertTrue(card.isAccessibilityFocused());
                assertEquals(context.getString(R.string.crosscheck_photo_empty),
                        card.createAccessibilityNodeInfo().getStateDescription());
                assertNull(controller(activity).currentBitmap());
                assertFalse(hasAction(card.createAccessibilityNodeInfo(), AccessibilityNodeInfo.ACTION_DISMISS));
            });
        }
    }

    @Test
    public void checkCompletionAndRetryKeepOneButtonAndUseOnlyThePoliteStatus() throws Exception {
        AnalysisPreferences.saveOpenRouterApiKey(context, "synthetic-key");
        AnalysisPreferences.saveFoodTypeModel(context, "test/vision");
        AllergenDictionary dictionary = AllergenDictionaryProvider.get(context);
        Allergen milk = dictionary.byId("MILK");
        AnalysisReport report = Analyzer.analyse("Ingredients: sugar, salt.", dictionary,
                Collections.singleton(milk));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger announcements = new AtomicInteger();
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
        assertFalse(report.label().ingredients().isEmpty());
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            File label = support.createLabel("INGREDIENTS: SUGAR, SALT.");
            support.loadPhoto(scan, label);
            awaitLabelPhotoReady(scan);
            AtomicReference<FoodCrossCheckController> ref = new AtomicReference<>();
            scan.onActivity(activity -> {
                ViewGroup parent = (ViewGroup) activity.findViewById(R.id.front_crosscheck_message).getParent();
                parent.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child,
                                                                            AccessibilityEvent event) {
                        if (event.getEventType() == AccessibilityEvent.TYPE_ANNOUNCEMENT) {
                            announcements.incrementAndGet();
                        }
                        return super.onRequestSendAccessibilityEvent(host, child, event);
                    }
                });
                // Replace the activity-owned controller so every layout/lifecycle callback uses
                // the injected model. Two controllers must not render into the same shared views.
                controller(activity).close();
                FoodCrossCheckController controller = new FoodCrossCheckController(activity, null,
                        () -> { }, (key, model) -> new FoodCrossCheckController.FrontPhotoModel() {
                    @Override public FoodTypeEstimate estimate(String data, java.util.List<Allergen> allergens)
                            throws Exception {
                        if (calls.incrementAndGet() == 1) {
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test release timed out");
                            throw new IOException("Synthetic network failure");
                        }
                        return new FoodTypeEstimate("cookies", Collections.singletonList(milk));
                    }
                    @Override public void close() { }
                });
                setController(activity, controller);
                controller.setOnPhotoChangedListener(() -> ScanTestSupport.invoke(activity,
                        "onFrontPhotoChanged", new Class<?>[0]));
                ref.set(controller);
                controller.onLabelPhoto(support.photoUri(label));
                controller.onReport(report);
                controller.setVisible(true);
            });
            FoodCrossCheckController test = ref.get();
            try {
                File photo = support.createLabel("FRONT PHOTO: COOKIES");
                scan.onActivity(activity -> test.loadPhoto(support.photoUri(photo)));
                await(scan, activity -> !test.isPhotoLoading());
                scan.onActivity(activity -> {
                    assertNotNull("The prepared front image must exist before inference", test.currentBitmap());
                    assertTrue("The optional result panel must be visible", activity.findViewById(
                            R.id.food_crosscheck_panel).isShown());
                    assertEquals("The fake model must receive the configured vision model", "test/vision",
                            AnalysisPreferences.foodTypeModel(activity));
                    assertEquals("Synthetic credentials must remain configured", "synthetic-key",
                            AnalysisPreferences.openRouterApiKey(activity));
                });
                assertTrue("The injected model was not reached after photo preparation", entered.await(5,
                        TimeUnit.SECONDS));
                AtomicReference<View> action = new AtomicReference<>();
                scan.onActivity(activity -> {
                    View run = activity.findViewById(R.id.front_crosscheck_run);
                    action.set(run);
                    assertFalse(run.isEnabled());
                    assertEquals(context.getString(R.string.crosscheck_running), run.getStateDescription());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, activity.findViewById(
                            R.id.front_crosscheck_progress).getImportantForAccessibility());
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, activity.findViewById(
                            R.id.front_crosscheck_message).getAccessibilityLiveRegion());
                });
                release.countDown();
                await(scan, activity -> message(activity).equals(context.getString(R.string.crosscheck_error)));
                scan.onActivity(activity -> {
                    View run = activity.findViewById(R.id.front_crosscheck_run);
                    assertSame(action.get(), run);
                    assertEquals(context.getString(R.string.crosscheck_retry), ((TextView) run).getText());
                    assertTrue(run.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                });
                await(scan, activity -> message(activity).contains("Common allergens to review"));
                scan.onActivity(activity -> {
                    assertSame(action.get(), activity.findViewById(R.id.front_crosscheck_run));
                    assertEquals(0, announcements.get());
                    AtomicInteger repeatedStatusWrites = new AtomicInteger();
                    ((TextView) activity.findViewById(R.id.front_crosscheck_message))
                            .addTextChangedListener(new android.text.TextWatcher() {
                                @Override public void beforeTextChanged(CharSequence s, int start,
                                                                         int count, int after) { }
                                @Override public void onTextChanged(CharSequence s, int start,
                                                                     int before, int count) {
                                    repeatedStatusWrites.incrementAndGet();
                                }
                                @Override public void afterTextChanged(android.text.Editable s) { }
                            });
                    test.setVisible(true);
                    test.onReport(report);
                    assertEquals("Unchanged advice must not repeat the polite status", 0,
                            repeatedStatusWrites.get());
                    assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
                });
            } finally {
                release.countDown();
                scan.onActivity(activity -> { test.removePhoto(); test.close(); });
            }
        }
    }

    private static void awaitLabelPhotoReady(ScanTestSupport.Scan scan) {
        try {
            // Retain the shared 10-second readiness condition and deadline exactly.
            ScanTestSupport.awaitPhotoReady(scan);
        } catch (AssertionError failure) {
            AtomicReference<String> diagnostic = new AtomicReference<>();
            scan.onActivity(activity -> {
                View start = activity.findViewById(R.id.start_analysis_button);
                ImageView preview = activity.findViewById(R.id.photo_preview);
                FoodCrossCheckController front = controller(activity);
                diagnostic.set("start=" + viewState(start)
                        + "; startAncestors=" + ancestors(start)
                        + "; preview=" + viewState(preview)
                        + "; previewDrawable=" + preview.getDrawable()
                        + "; previewAncestors=" + ancestors(preview)
                        + "; selectedPhoto=" + mainField(activity, "selectedPhoto")
                        + "; pendingPhoto=" + mainField(activity, "pendingPhoto")
                        + "; imageRequest=" + mainField(activity, "imageRequest")
                        + "; analysisMode=" + mainField(activity, "analysisMode")
                        + "; settingsOpen=" + mainField(activity, "settingsOpen")
                        + "; historyVisible=" + mainField(activity, "historyVisible")
                        + "; loadingOverlay=" + viewState(activity.findViewById(R.id.loading_overlay))
                        + "; lifecycle=" + activity.getLifecycle().getCurrentState()
                        + "; hasWindowFocus=" + activity.hasWindowFocus()
                        + "; frontLoading=" + front.isPhotoLoading()
                        + "; frontInteractionOpen=" + front.isPhotoInteractionOpen());
            });
            throw new AssertionError(failure.getMessage() + ": " + diagnostic.get(), failure);
        }
    }

    private static String viewState(View view) {
        String name = view.getId() == View.NO_ID ? view.getClass().getSimpleName()
                : view.getResources().getResourceEntryName(view.getId());
        return name + "[visibility=" + view.getVisibility() + ", shown=" + view.isShown()
                + ", enabled=" + view.isEnabled() + ", attached=" + view.isAttachedToWindow()
                + ", alpha=" + view.getAlpha() + ", size=" + view.getWidth() + "x" + view.getHeight() + "]";
    }

    private static String ancestors(View view) {
        StringBuilder state = new StringBuilder();
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View;
                parent = parent.getParent()) {
            if (state.length() > 0) state.append(" -> ");
            state.append(viewState((View) parent));
        }
        return state.toString();
    }

    private static Object mainField(MainActivity activity, String name) {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static FoodCrossCheckController controller(MainActivity activity) {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("foodCrossCheck");
            field.setAccessible(true);
            return (FoodCrossCheckController) field.get(activity);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void setController(MainActivity activity, FoodCrossCheckController controller) {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("foodCrossCheck");
            field.setAccessible(true);
            field.set(activity, controller);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static String message(MainActivity activity) {
        return ((TextView) activity.findViewById(R.id.front_crosscheck_message)).getText().toString();
    }

    private void assertAction(AccessibilityNodeInfo node, int id, int label) {
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId() == id) {
                assertEquals(context.getString(label), action.getLabel());
                return;
            }
        }
        fail("Missing accessible action " + context.getString(label));
    }

    private static boolean hasAction(AccessibilityNodeInfo node, int id) {
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId() == id) return true;
        }
        return false;
    }

    private static void await(ScanTestSupport.Scan scan, Predicate<MainActivity> predicate) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            boolean[] ready = {false};
            scan.onActivity(activity -> ready[0] = predicate.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Front photo did not reach the expected state");
    }
}
