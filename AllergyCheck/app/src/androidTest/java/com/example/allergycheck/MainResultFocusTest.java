package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Component tests of real AX nodes; native TalkBack window decisions are accepted separately. */
@RunWith(AndroidJUnit4.class)
public class MainResultFocusTest {
    private Context context;
    private UiAutomation automation;
    private AccessibilityServiceInfo originalServiceInfo;
    private final Map<String, Map<String, ?>> previous = new HashMap<>();

    @Before
    public void isolatePreferences() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // A direct test-driver focus action is not a TalkBack logical-navigation gesture.
        // Isolate component callbacks from the external service's pending window transitions;
        // actual TalkBack gestures/window restoration are verified in the emulator acceptance.
        automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        originalServiceInfo = automation.getServiceInfo();
        AccessibilityServiceInfo componentService = automation.getServiceInfo();
        componentService.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        automation.setServiceInfo(componentService);
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!manager.isTouchExplorationEnabled() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25);
        }
        assertTrue("Component focus requires touch exploration", manager.isTouchExplorationEnabled());
        for (String name : new String[] {"allergy_preferences", "analysis_preferences"}) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            previous.put(name, new HashMap<>(preferences.getAll()));
        }
        assertTrue(AllergenPreferences.preferences(context).edit().remove("selected_photo")
                .putStringSet("selected_allergens", Collections.singleton("MILK")).commit());
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
    }

    @After
    @SuppressWarnings("unchecked")
    public void restorePreferences() {
        if (automation != null && originalServiceInfo != null) {
            automation.setServiceInfo(originalServiceInfo);
        }
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
    public void completedResultOffersItsHeadingWhenProgressWasTheReadingPosition() {
        try (ActivityScenario<MainActivity> screen = launch()) {
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                enterAnalysis(activity);
                ScanTestSupport.invoke(activity, "showAnalysisLoading", new Class<?>[0]);
            });
            await(screen, activity -> activity.findViewById(R.id.analysis_loading_title).isLaidOut());
            screen.onActivity(activity -> {
                focus(activity.findViewById(R.id.analysis_loading_title));
                revealMessage(activity, "Completed result", "NONE");
                View title = activity.findViewById(R.id.verdict_title);
                assertTrue(title.isShown());
                assertTrue(title.createAccessibilityNodeInfo().hasRequestInitialAccessibilityFocus());
                assertFalse(hasInitialHint(activity, R.id.analysis_loading_title));
                assertEquals(activity.getString(R.string.result_ready, "Completed result"),
                        title.getAccessibilityPaneTitle());
            });
        }
    }

    @Test
    public void completionDoesNotReplaceTheReadersToolbarPosition() {
        try (ActivityScenario<MainActivity> screen = launch()) {
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                enterAnalysis(activity);
                focus(activity.findViewById(R.id.settings_button));
                revealMessage(activity, "Completed result", "NONE");
                assertFalse(hasInitialHint(activity, R.id.verdict_title));
            });
            assertReadingPositionSurvives(screen, R.id.settings_button);
        }
    }

    @Test
    public void aNewReadingPositionDuringResultRevealIsNotOverwrittenLater() {
        try (ActivityScenario<MainActivity> screen = launch()) {
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                enterAnalysis(activity);
                revealMessage(activity, "Completed result", "NONE");
            });
            await(screen, activity -> activity.findViewById(R.id.result_caution).isLaidOut());
            screen.onActivity(activity -> focus(activity.findViewById(R.id.result_caution)));
            screen.onActivity(activity -> assertFalse(hasInitialHint(activity, R.id.verdict_title)));
            assertReadingPositionSurvives(screen, R.id.result_caution);
        }
    }

    @Test
    public void pausingClearsTheOldInitialHintBeforeReturning() {
        try (ActivityScenario<MainActivity> screen = launch()) {
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                enterAnalysis(activity);
                revealMessage(activity, "Completed result", "NONE");
            });
            screen.moveToState(Lifecycle.State.CREATED);
            screen.moveToState(Lifecycle.State.RESUMED);
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                assertFalse(hasInitialHint(activity, R.id.verdict_title));
                focus(activity.findViewById(R.id.settings_button));
                assertFalse(hasInitialHint(activity, R.id.allergens_button));
            });
            assertReadingPositionSurvives(screen, R.id.settings_button);
        }
    }

    @Test
    public void historyRoundTripDoesNotResurrectThePreviousResultHint() {
        try (ActivityScenario<MainActivity> screen = launch()) {
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                enterAnalysis(activity);
                revealMessage(activity, "Completed result", "NONE");
                BottomNavigationView navigation = activity.findViewById(R.id.bottom_nav);
                navigation.setSelectedItemId(R.id.tab_history);
                navigation.setSelectedItemId(R.id.tab_scan);
                assertFalse(hasInitialHint(activity, R.id.verdict_title));
                focus(activity.findViewById(R.id.tab_scan));
            });
            assertReadingPositionSurvives(screen, R.id.tab_scan);
        }
    }

    @Test
    public void cancelledAllergenDialogKeepsItsStableCallerAsANativeFallback() {
        verifyHomeDialog(false, false);
    }

    @Test
    public void savingAllergensUpdatesTheSameCallerWithoutDelayedFocus() {
        verifyHomeDialog(true, false);
    }

    @Test
    public void recreatedAllergenDialogRetainsItsOriginalFallbackCaller() {
        verifyHomeDialog(false, true);
    }

    private void verifyHomeDialog(boolean save, boolean recreate) {
        try (ActivityScenario<MainActivity> screen = launch()) {
            await(screen, MainActivity::hasWindowFocus);
            View[] original = {null};
            screen.onActivity(activity -> {
                original[0] = activity.findViewById(R.id.allergens_button);
                focus(original[0]);
                original[0].performClick();
            });
            if (recreate) screen.recreate();
            clickDialogButton(screen, save ? android.R.id.button1 : android.R.id.button2);
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                View caller = activity.findViewById(R.id.allergens_button);
                if (!recreate) assertSame(original[0], caller);
                assertTrue(caller.isShown());
                assertTrue(hasInitialHint(activity, R.id.allergens_button));
                assertFalse(hasInitialHint(activity, R.id.verdict_title));
                focus(activity.findViewById(R.id.settings_button));
            });
            assertReadingPositionSurvives(screen, R.id.settings_button);
        }
    }

    @Test
    public void cancellingTheResultDialogDoesNotOverrideANewReadingPosition() {
        try (ActivityScenario<MainActivity> screen = launch()) {
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                enterAnalysis(activity);
                revealMessage(activity, "Choose allergens", "CHOOSE_ALLERGENS");
            });
            await(screen, activity -> activity.findViewById(R.id.result_secondary_button).isLaidOut());
            View[] caller = {null};
            screen.onActivity(activity -> {
                caller[0] = activity.findViewById(R.id.result_secondary_button);
                focus(caller[0]);
                caller[0].performClick();
            });
            clickDialogButton(screen, android.R.id.button2);
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                assertSame(caller[0], activity.findViewById(R.id.result_secondary_button));
                assertTrue(caller[0].isShown());
                assertTrue(caller[0].isEnabled());
                assertFalse(hasInitialHint(activity, R.id.verdict_title));
                focus(activity.findViewById(R.id.result_caution));
                assertFalse(hasInitialHint(activity, R.id.result_secondary_button));
            });
            assertReadingPositionSurvives(screen, R.id.result_caution);
        }
    }

    @Test
    public void leavingThePageClearsTheDialogReturnHint() {
        try (ActivityScenario<MainActivity> screen = launch()) {
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> activity.findViewById(R.id.allergens_button).performClick());
            clickDialogButton(screen, android.R.id.button2);
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                BottomNavigationView navigation = activity.findViewById(R.id.bottom_nav);
                navigation.setSelectedItemId(R.id.tab_history);
                navigation.setSelectedItemId(R.id.tab_scan);
                assertFalse(hasInitialHint(activity, R.id.allergens_button));
            });
        }
    }

    @Test
    public void modalKeepsItsCallerVisibleWithoutScrollingAfterDismissal() {
        try (ActivityScenario<MainActivity> screen = launch()) {
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                activity.findViewById(R.id.allergens_button).performClick();
                View scroll = activity.findViewById(R.id.content_scroll);
                ViewGroup.LayoutParams params = scroll.getLayoutParams();
                params.height = (int) (200 * activity.getResources().getDisplayMetrics().density);
                scroll.setLayoutParams(params);
            });
            await(screen, activity -> {
                View caller = activity.findViewById(R.id.allergens_button);
                Rect visible = new Rect();
                return caller.getHeight() > 0 && caller.getGlobalVisibleRect(visible)
                        && visible.height() == caller.getHeight();
            });
            clickDialogButton(screen, android.R.id.button2);
            await(screen, MainActivity::hasWindowFocus);
            screen.onActivity(activity -> {
                View scroll = activity.findViewById(R.id.content_scroll);
                scroll.scrollTo(0, 0);
                focus(activity.findViewById(R.id.settings_button));
                scroll.requestLayout();
            });
            assertReadingPositionSurvives(screen, R.id.settings_button);
            screen.onActivity(activity -> assertEquals("A closed modal must not keep scrolling",
                    0, activity.findViewById(R.id.content_scroll).getScrollY()));
        }
    }

    private static boolean hasInitialHint(MainActivity activity, int id) {
        return activity.findViewById(id).createAccessibilityNodeInfo()
                .hasRequestInitialAccessibilityFocus();
    }

    private static void focus(View target) {
        if (!target.isAccessibilityFocused()) assertTrue("A real reading position is required",
                target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        assertTrue(target.isAccessibilityFocused());
    }

    private static void assertReadingPositionSurvives(ActivityScenario<MainActivity> screen, int id) {
        // The previous implementation issued a delayed app action after the pane / animation.
        SystemClock.sleep(1100);
        screen.onActivity(activity -> assertTrue("The reader's new position must survive completion",
                activity.findViewById(id).isAccessibilityFocused()));
    }

    private static void await(ActivityScenario<MainActivity> screen, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            boolean[] ready = {false};
            screen.onActivity(activity -> ready[0] = condition.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The expected visible window state did not become ready");
    }

    private static void clickDialogButton(ActivityScenario<MainActivity> screen, int id) {
        await(screen, activity -> {
            for (View window : WindowInspector.getGlobalWindowViews()) {
                if (window == activity.getWindow().getDecorView()
                        || !window.hasWindowFocus() || !window.isShown()) continue;
                View button = window.findViewById(id);
                if (button != null && button.isShown() && button.isEnabled()) {
                    return button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null);
                }
            }
            return false;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(context, MainActivity.class));
    }

    private static void enterAnalysis(MainActivity activity) {
        ScanTestSupport.invoke(activity, "setAnalysisMode", new Class<?>[] {boolean.class}, true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void revealMessage(MainActivity activity, String title, String actionName) {
        new AnalysisResultView((ViewGroup) activity.findViewById(R.id.analysis_result))
                .showMessage(title, "Result body");
        try {
            Class action = Class.forName(MainActivity.class.getName() + "$ResultAction");
            ScanTestSupport.invoke(activity, "revealResult", new Class<?>[] {action},
                    Enum.valueOf(action, actionName));
        } catch (ClassNotFoundException exception) {
            throw new AssertionError(exception);
        }
    }
}
