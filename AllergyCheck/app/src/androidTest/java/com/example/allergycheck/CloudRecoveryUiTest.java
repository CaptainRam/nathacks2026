package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.AiAnalyzer;
import com.example.allergycheck.analysis.Analyzer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Real result rendering/action handlers with typed reports; no provider request or history write. */
@RunWith(AndroidJUnit4.class)
public class CloudRecoveryUiTest {
    private Context context;
    private AnalysisReport incomplete;
    private final Map<String, Map<String, ?>> previous = new HashMap<>();
    private final CountDownLatch releaseOcr = new CountDownLatch(1);

    @Before
    public void isolateConfiguration() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[] {"allergy_preferences", "analysis_preferences", "analysis_credentials"}) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            previous.put(name, new HashMap<>(preferences.getAll()));
        }
        assertTrue(AllergenPreferences.preferences(context).edit().remove("selected_photo")
                .putStringSet("selected_allergens", Collections.singleton("MILK")).commit());
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.OPENROUTER);
        AnalysisPreferences.saveOpenRouterApiKey(context, "test-cloud-recovery-key");
        AnalysisPreferences.saveOpenRouterModel(context, "example/model");
        AllergenDictionary dictionary = AllergenDictionaryProvider.get(context);
        incomplete = AiAnalyzer.incompleteReport(Analyzer.analyse("Ingredients: milk, sugar, salt.",
                dictionary, Collections.singletonList(dictionary.byId("MILK"))));
    }

    @After
    @SuppressWarnings("unchecked")
    public void restoreConfiguration() {
        releaseOcr.countDown();
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
                else if (value instanceof Set) editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>) value));
            }
            assertTrue(editor.commit());
        }
    }

    @Test
    public void permanentCloudFailuresExplainTheCauseAndOpenConfigurationWithoutLosingEvidence() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                SettingsActivity.class.getName(), null, false);
        Activity settings = null;
        try (ActivityScenario<MainActivity> screen = launch()) {
            screen.onActivity(activity -> {
                AnalysisReport.AiFailure[] failures = {AnalysisReport.AiFailure.MODEL,
                        AnalysisReport.AiFailure.QUOTA, AnalysisReport.AiFailure.AUTHENTICATION};
                int[] causes = {R.string.ai_failure_model, R.string.ai_failure_quota,
                        R.string.ai_failure_authentication};
                for (int index = 0; index < failures.length; index++) {
                    render(activity, incomplete.withAiFailure(failures[index], 0));
                    TextView title = activity.findViewById(R.id.verdict_title);
                    assertEquals(context.getString(R.string.status_contains_title), title.getText().toString());
                    TextView note = activity.findViewById(R.id.low_confidence_note);
                    assertTrue(note.isShown());
                    assertTrue(note.getText().toString().contains(context.getString(causes[index])));
                    assertTrue(note.getText().toString().contains(
                            context.getString(R.string.ai_analysis_incomplete_note)));
                    assertFalse(note.getText().toString().contains("test-cloud-recovery-key"));
                    assertTrue(((TextView) activity.findViewById(R.id.allergen_evidence))
                            .getText().toString().contains("matches milk"));
                    TextView action = activity.findViewById(R.id.result_secondary_button);
                    assertEquals(context.getString(R.string.configure_ai_action), action.getText().toString());
                    assertTrue(action.isEnabled());
                }
                assertTrue(activity.findViewById(R.id.result_secondary_button)
                        .performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
            });
            settings = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull("The repair action must open Settings", settings);
            assertTrue(settings.getIntent().getBooleanExtra(SettingsActivity.EXTRA_SHOW_MODEL, false));
        } finally {
            Activity opened = settings;
            if (opened != null) instrumentation.runOnMainSync(opened::finish);
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test
    public void rateLimitDisablesRetryUntilItsDeadlineAndRejectsAnEarlyHandlerCall() throws Exception {
        try (ActivityScenario<MainActivity> screen = launch()) {
            CountDownLatch blocked = new CountDownLatch(1);
            screen.onActivity(activity -> {
                ExecutorService executor = (ExecutorService) field(field(activity, "ocrPipeline"), "imageExecutor");
                executor.execute(() -> {
                    blocked.countDown();
                    try { releaseOcr.await(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                });
                Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                ((ImageView) activity.findViewById(R.id.photo_preview)).setImageBitmap(bitmap);
                setField(activity, "selectedPhoto", Uri.parse("content://com.example.allergycheck.fileprovider/photos/not-opened.png"));
                render(activity, incomplete.withAiFailure(AnalysisReport.AiFailure.RATE_LIMIT,
                        System.currentTimeMillis() + 1500));
                View action = activity.findViewById(R.id.result_secondary_button);
                assertFalse(action.isEnabled());
                assertEquals(context.getString(R.string.ai_retry_wait_state),
                        action.createAccessibilityNodeInfo().getStateDescription());
                int before = (Integer) field(activity, "imageRequest");
                // A forced framework action can still return true for a disabled clickable
                // View. Its handler must independently prevent the early provider retry.
                action.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null);
                assertEquals(before, field(activity, "imageRequest"));
                // Even a direct callback invocation must not start another scan before the limit.
                ScanTestSupport.invoke(activity, "onResultAction", new Class<?>[0]);
                assertEquals(before, field(activity, "imageRequest"));
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            awaitEnabled(screen);
            screen.onActivity(activity -> {
                TextView action = activity.findViewById(R.id.result_secondary_button);
                assertEquals(context.getString(R.string.try_again), action.getText().toString());
                assertTrue(action.isEnabled());
            });
        } finally {
            releaseOcr.countDown();
        }
    }

    @Test
    public void anOldRetryDeadlineCannotOverwriteANewerConfigurationAction() {
        try (ActivityScenario<MainActivity> screen = launch()) {
            screen.onActivity(activity -> {
                render(activity, incomplete.withAiFailure(AnalysisReport.AiFailure.RATE_LIMIT,
                        System.currentTimeMillis() + 600));
                ScanTestSupport.invoke(activity, "showAnalysisLoading", new Class<?>[0]);
                render(activity, incomplete.withAiFailure(AnalysisReport.AiFailure.AUTHENTICATION, 0));
            });
            screen.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);
            SystemClock.sleep(800);
            screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            screen.onActivity(activity -> {
                TextView action = activity.findViewById(R.id.result_secondary_button);
                assertEquals(context.getString(R.string.configure_ai_action), action.getText().toString());
                assertTrue(action.isEnabled());
                assertTrue(((TextView) activity.findViewById(R.id.low_confidence_note)).getText().toString()
                        .contains(context.getString(R.string.ai_failure_authentication)));
            });
        }
    }

    @Test
    public void readErrorsAndReportsWithoutAiRequestsDoNotEraseTheProviderWait() {
        try (ActivityScenario<MainActivity> screen = launch()) {
            screen.onActivity(activity -> {
                long deadline = System.currentTimeMillis() + 60_000;
                render(activity, incomplete.withAiFailure(AnalysisReport.AiFailure.RATE_LIMIT, deadline));
                ScanTestSupport.invoke(activity, "bindReadFailureMessage",
                        new Class<?>[] {String.class, String.class}, "Ingredient extraction", "The photo could not be read.");
                assertEquals(deadline, field(activity, "aiRetryAtMillis"));
                assertEquals(0L, field(activity, "resultRetryAtMillis"));

                AllergenDictionary dictionary = AllergenDictionaryProvider.get(context);
                AnalysisReport matching = Analyzer.analyse("Ingredients: milk.", dictionary,
                        Collections.singletonList(dictionary.byId("MILK")));
                render(activity, matching);
                assertEquals(deadline, field(activity, "aiRetryAtMillis"));
                assertEquals(0L, field(activity, "resultRetryAtMillis"));
                assertEquals(View.GONE, activity.findViewById(R.id.result_secondary_button).getVisibility());
            });
        }
    }

    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(context, MainActivity.class));
    }

    private static void render(MainActivity activity, AnalysisReport report) {
        ScanTestSupport.invoke(activity, "setAnalysisMode", new Class<?>[] {boolean.class}, true);
        try {
            Method bind = MainActivity.class.getDeclaredMethod("bindAnalysisReport", AnalysisReport.class);
            bind.setAccessible(true);
            Object action = bind.invoke(activity, report);
            Method reveal = MainActivity.class.getDeclaredMethod("revealResult", action.getClass());
            reveal.setAccessible(true);
            reveal.invoke(activity, action);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void setField(Object owner, String name, Object value) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void awaitEnabled(ActivityScenario<MainActivity> screen) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            boolean[] ready = {false};
            screen.onActivity(activity -> ready[0] = activity.findViewById(R.id.result_secondary_button).isEnabled());
            if (ready[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Retry did not become available after its deadline");
    }
}
