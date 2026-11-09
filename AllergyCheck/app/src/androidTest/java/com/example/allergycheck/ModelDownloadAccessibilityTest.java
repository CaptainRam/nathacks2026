package com.example.allergycheck;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.ingredients.LocalModelManager;
import com.google.android.material.snackbar.Snackbar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Simulates status callbacks without enqueueing a download or changing the model manager. */
@RunWith(AndroidJUnit4.class)
public class ModelDownloadAccessibilityTest {
    private Context context;
    private Map<String, ?> originalPreferences;

    @Before
    public void preservePreferences() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalPreferences = new HashMap<>(AllergenPreferences.preferences(context).getAll());
    }

    @After
    @SuppressWarnings("unchecked")
    public void restorePreferences() {
        SharedPreferences.Editor editor = AllergenPreferences.preferences(context).edit().clear();
        for (Map.Entry<String, ?> entry : originalPreferences.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>) value));
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else editor.putString(entry.getKey(), (String) value);
        }
        assertTrue(editor.commit());
    }

    @Test
    public void startAndCompletionHaveVisibleNoticesWithoutPerPercentAnnouncements() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                attachCard(activity);
                ModelDownloadController controller = new ModelDownloadController(activity, () -> { });
                try {
                    render(controller, LocalModelManager.State.MISSING, 0);
                    assertNull(notice(controller));
                    render(controller, LocalModelManager.State.DOWNLOADING, 0);
                    Snackbar started = notice(controller);
                    assertNotice(started, R.string.model_download_notification_description);
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                            activity.findViewById(R.id.model_status_message).getAccessibilityLiveRegion());
                    render(controller, LocalModelManager.State.DOWNLOADING, 40);
                    assertSame(started, notice(controller));
                    render(controller, LocalModelManager.State.INSTALLING, 100);
                    render(controller, LocalModelManager.State.READY, 100);
                    Snackbar completed = notice(controller);
                    assertNotSame(started, completed);
                    assertNotice(completed, R.string.model_ready_status);
                    assertEquals(View.GONE, activity.findViewById(R.id.model_download_card).getVisibility());
                    render(controller, LocalModelManager.State.READY, 100);
                    assertSame(completed, notice(controller));
                } finally {
                    controller.stop();
                }
            });
        }
    }

    @Test
    public void launchSnapshotsAndHiddenOrUnneededCardsDoNotProduceNotices() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                FrameLayout host = attachCard(activity);
                AtomicBoolean needed = new AtomicBoolean(true);
                ModelDownloadController controller = new ModelDownloadController(activity, () -> { }, needed::get);
                try {
                    render(controller, LocalModelManager.State.READY, 100);
                    assertNull(notice(controller));
                    render(controller, LocalModelManager.State.MISSING, 0);
                    host.setVisibility(View.GONE);
                    render(controller, LocalModelManager.State.DOWNLOADING, 0);
                    assertNull(notice(controller));
                    host.setVisibility(View.VISIBLE);
                    render(controller, LocalModelManager.State.DOWNLOADING, 40);
                    assertNull(notice(controller));
                    needed.set(false);
                    render(controller, LocalModelManager.State.READY, 100);
                    needed.set(true);
                    render(controller, LocalModelManager.State.READY, 100);
                    assertNull(notice(controller));
                } finally {
                    controller.stop();
                }
            });
        }
    }

    private FrameLayout attachCard(SettingsActivity activity) {
        FrameLayout host = new FrameLayout(activity);
        activity.getLayoutInflater().inflate(R.layout.view_model_download, host, true);
        activity.addContentView(host, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return host;
    }

    private void assertNotice(Snackbar snackbar, int textResource) {
        assertNotNull(snackbar);
        assertTrue(snackbar.isShownOrQueued());
        TextView text = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        assertEquals(context.getString(textResource), text.getText().toString());
    }

    private static void render(ModelDownloadController controller, LocalModelManager.State state, long percent) {
        try {
            Constructor<LocalModelManager.Status> constructor = LocalModelManager.Status.class.getDeclaredConstructor(
                    LocalModelManager.State.class, long.class, long.class, String.class);
            constructor.setAccessible(true);
            Method render = ModelDownloadController.class.getDeclaredMethod("render", LocalModelManager.Status.class);
            render.setAccessible(true);
            render.invoke(controller, constructor.newInstance(state, percent, 100L, null));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Snackbar notice(ModelDownloadController controller) {
        try {
            Field field = ModelDownloadController.class.getDeclaredField("statusNotice");
            field.setAccessible(true);
            return (Snackbar) field.get(controller);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
