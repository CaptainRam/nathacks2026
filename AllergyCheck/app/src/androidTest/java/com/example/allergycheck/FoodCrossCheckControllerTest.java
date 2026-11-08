package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.crosscheck.FoodTypeEstimate;
import com.example.allergycheck.crosscheck.UnknownFoodTypeException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real image preparation and main-loop cancellation with a fake cloud model; no network. */
@RunWith(AndroidJUnit4.class)
public class FoodCrossCheckControllerTest {
    private Context context;
    private ScanTestSupport support;
    private Allergen milk;
    private AnalysisReport report;
    private final Map<String, Map<String, ?>> previous = new HashMap<>();

    @Before
    public void isolateConfiguration() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[]{"analysis_preferences", "analysis_credentials", "allergy_preferences"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            previous.put(name, new HashMap<>(prefs.getAll()));
            assertTrue(prefs.edit().clear().commit());
        }
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
        AnalysisPreferences.saveOpenRouterApiKey(context, "test-front-photo-key");
        AnalysisPreferences.saveFoodTypeModel(context, "test/vision-model");
        AllergenDictionary dictionary = AllergenDictionaryProvider.get(context);
        milk = dictionary.byId("MILK");
        assertNotNull(milk);
        report = Analyzer.analyse("Ingredients: sugar, salt, cocoa.", dictionary,
                Collections.singleton(milk));
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
        support = new ScanTestSupport(context);
    }

    @After
    @SuppressWarnings("unchecked")
    public void restoreConfiguration() {
        if (support != null) support.close();
        for (Map.Entry<String, Map<String, ?>> file : previous.entrySet()) {
            SharedPreferences.Editor editor = context.getSharedPreferences(
                    file.getKey(), Context.MODE_PRIVATE).edit().clear();
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
    public void optionalPhotoWaitsForLabelThenAddsOnlyAnIndependentReminder() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> image = new AtomicReference<>();
        byte[] original = serialized(report);
        File front = support.createLabel("FRONT PHOTO: COOKIES");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            FoodCrossCheckController controller = create(scan, null, (key, model) -> model((data, selected) -> {
                calls.incrementAndGet();
                image.set(data);
                assertEquals(Collections.singletonList(milk), selected);
                return cookies();
            }));
            try {
                scan.onActivity(activity -> {
                    controller.onLabelPhoto(Uri.parse("content://test/ingredient-label"));
                    controller.setVisible(true);
                    controller.loadPhoto(support.photoUri(front));
                });
                awaitPhoto(scan);
                assertEquals("A front photo alone must never replace the required label analysis", 0, calls.get());
                scan.onActivity(activity -> controller.onReport(report));
                awaitMessage(scan, "Common allergens to review");
                assertEquals(1, calls.get());
                assertTrue(image.get().startsWith("data:image/jpeg;base64,"));
                scan.onActivity(activity -> {
                    String reminder = message(activity);
                    assertTrue(reminder.contains("Milk"));
                    assertTrue(reminder.contains("does not confirm"));
                    assertTrue(reminder.contains("not identified"));
                });
                assertArrayEquals("The front-photo inference must not change the ingredient report", original, serialized(report));
                assertEquals(AnalysisReport.Status.NOT_DETECTED, report.results().get(0).status());
                assertTrue(report.results().get(0).findings().isEmpty());
            } finally {
                scan.onActivity(activity -> { controller.removePhoto(); controller.close(); });
            }
        }
    }

    @Test
    public void failedCloudCheckKeepsLabelResultAndCanBeRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        File front = support.createLabel("FRONT PHOTO");
        byte[] original = serialized(report);
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            FoodCrossCheckController controller = create(scan, null, (key, model) -> model((data, selected) -> {
                if (calls.incrementAndGet() == 1) throw new java.io.IOException("synthetic connection failure");
                return cookies();
            }));
            try {
                scan.onActivity(activity -> {
                    controller.onLabelPhoto(Uri.parse("content://test/ingredient-label"));
                    controller.onReport(report);
                    controller.setVisible(true);
                    controller.loadPhoto(support.photoUri(front));
                });
                awaitMessage(scan, "could not be completed");
                scan.onActivity(activity -> {
                    assertTrue(activity.findViewById(R.id.front_crosscheck_run).isEnabled());
                    controller.runCheck();
                });
                awaitMessage(scan, "Common allergens to review");
                assertEquals(2, calls.get());
                assertArrayEquals(original, serialized(report));
            } finally {
                scan.onActivity(activity -> { controller.removePhoto(); controller.close(); });
            }
        }
    }

    @Test
    public void unknownFoodTypeIsInconclusiveAndRetryableWithoutChangingTheLabel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        File front = support.createLabel("UNCLEAR FRONT PHOTO");
        byte[] original = serialized(report);
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            FoodCrossCheckController controller = create(scan, null, (key, model) -> model((data, selected) -> {
                if (calls.incrementAndGet() == 1) throw new UnknownFoodTypeException();
                return cookies();
            }));
            try {
                scan.onActivity(activity -> {
                    controller.onLabelPhoto(Uri.parse("content://test/ingredient-label"));
                    controller.onReport(report);
                    controller.setVisible(true);
                    controller.loadPhoto(support.photoUri(front));
                });
                awaitMessage(scan, "could not identify a food type");
                scan.onActivity(activity -> {
                    assertFalse("Unknown food must not look like a successful no-reminder check",
                            message(activity).contains("No additional reminders"));
                    assertTrue(activity.findViewById(R.id.front_crosscheck_run).isEnabled());
                    assertEquals(activity.getString(R.string.crosscheck_retry),
                            ((TextView) activity.findViewById(R.id.front_crosscheck_run)).getText().toString());
                    controller.runCheck();
                });
                awaitMessage(scan, "Common allergens to review");
                assertEquals(2, calls.get());
                assertArrayEquals(original, serialized(report));
            } finally {
                scan.onActivity(activity -> { controller.removePhoto(); controller.close(); });
            }
        }
    }

    @Test
    public void removingPhotoCancelsBlockedRequestAndDropsLateReminder() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        File front = support.createLabel("FRONT PHOTO");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            FoodCrossCheckController controller = create(scan, null, (key, model) -> new FoodCrossCheckController.FrontPhotoModel() {
                @Override
                public FoodTypeEstimate estimate(String image, List<Allergen> selected) throws Exception {
                    entered.countDown();
                    // Simulate a service returning even after cancellation was requested.
                    long deadline = SystemClock.uptimeMillis() + 5_000;
                    while (release.getCount() != 0 && SystemClock.uptimeMillis() < deadline) {
                        try { release.await(100, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { }
                    }
                    return cookies();
                }
                @Override public void close() { closed.countDown(); }
            });
            try {
                scan.onActivity(activity -> {
                    controller.onLabelPhoto(Uri.parse("content://test/ingredient-label"));
                    controller.onReport(report);
                    controller.setVisible(true);
                    controller.loadPhoto(support.photoUri(front));
                });
                assertTrue("The fake cloud request did not start", entered.await(5, TimeUnit.SECONDS));
                scan.onActivity(activity -> controller.removePhoto());
                assertTrue("Removing the image must close its active model", closed.await(5, TimeUnit.SECONDS));
                release.countDown();
                awaitNetworkIdle(controller);
                // A rerun is impossible once the photo is removed, even if a late answer arrives.
                scan.onActivity(activity -> {
                    assertEquals(View.GONE, activity.findViewById(R.id.front_photo_preview).getVisibility());
                    assertFalse(activity.findViewById(R.id.front_crosscheck_run).isEnabled());
                    assertFalse(message(activity).contains("Estimated food type"));
                });
            } finally {
                release.countDown();
                scan.onActivity(activity -> { controller.removePhoto(); controller.close(); });
            }
        }
    }

    @Test
    public void replacingIngredientPhotoClearsThePreviousProductsFrontPhoto() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        File front = support.createLabel("FRONT PHOTO");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            FoodCrossCheckController controller = create(scan, null, (key, model) -> model((data, selected) -> {
                calls.incrementAndGet();
                return cookies();
            }));
            try {
                scan.onActivity(activity -> {
                    controller.onLabelPhoto(Uri.parse("content://test/product-one"));
                    controller.loadPhoto(support.photoUri(front));
                });
                awaitPhoto(scan);
                scan.onActivity(activity -> {
                    controller.onLabelPhoto(Uri.parse("content://test/product-two"));
                    controller.onReport(report);
                    assertEquals(View.GONE, activity.findViewById(R.id.front_photo_preview).getVisibility());
                    assertFalse(activity.findViewById(R.id.front_crosscheck_run).isEnabled());
                });
                assertEquals("A new product needs its own optional front photo", 0, calls.get());
            } finally {
                scan.onActivity(activity -> { controller.removePhoto(); controller.close(); });
            }
        }
    }

    @Test
    public void suspendedPhotoDecodeWaitsForSettingsReturnBeforeCloudUpload() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch imageWorkerEntered = new CountDownLatch(1);
        CountDownLatch releaseImageWorker = new CountDownLatch(1);
        File front = support.createLabel("FRONT PHOTO");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            FoodCrossCheckController controller = create(scan, null, (key, model) -> model((data, selected) -> {
                calls.incrementAndGet();
                return cookies();
            }));
            Field field = FoodCrossCheckController.class.getDeclaredField("images");
            field.setAccessible(true);
            ExecutorService images = (ExecutorService) field.get(controller);
            images.execute(() -> {
                imageWorkerEntered.countDown();
                try { releaseImageWorker.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            try {
                assertTrue(imageWorkerEntered.await(5, TimeUnit.SECONDS));
                scan.onActivity(activity -> {
                    controller.onLabelPhoto(Uri.parse("content://test/ingredient-label"));
                    controller.onReport(report);
                    controller.loadPhoto(support.photoUri(front));
                    controller.suspend();
                });
                releaseImageWorker.countDown();
                awaitPhoto(scan);
                assertEquals("Finishing photo decode behind Settings must not start an upload", 0, calls.get());
                scan.onActivity(activity -> {
                    assertFalse(activity.findViewById(R.id.front_crosscheck_run).isEnabled());
                    controller.refreshConfiguration();
                });
                awaitMessage(scan, "Common allergens to review");
                assertEquals("Returning from Settings should resume the optional check once", 1, calls.get());
            } finally {
                releaseImageWorker.countDown();
                scan.onActivity(activity -> { controller.removePhoto(); controller.close(); });
            }
        }
    }

    @Test
    public void savedCompletedEstimateIsReusedWithoutAnotherUpload() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        File front = support.createLabel("FRONT PHOTO");
        Bundle saved = new Bundle();
        FoodCrossCheckController.ModelFactory factory = (key, model) -> model((data, selected) -> {
            calls.incrementAndGet();
            return cookies();
        });
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            FoodCrossCheckController first = create(scan, null, factory);
            scan.onActivity(activity -> {
                first.onLabelPhoto(Uri.parse("content://test/ingredient-label"));
                first.onReport(report);
                first.loadPhoto(support.photoUri(front));
            });
            awaitMessage(scan, "Common allergens to review");
            scan.onActivity(activity -> {
                first.saveState(saved);
                first.close();
            });
            FoodCrossCheckController restored = create(scan, saved, factory);
            try {
                scan.onActivity(activity -> {
                    restored.onLabelPhoto(Uri.parse("content://test/ingredient-label"));
                    restored.onReport(report);
                });
                awaitMessage(scan, "Common allergens to review");
                assertEquals("Activity restoration must reuse a completed estimate", 1, calls.get());
            } finally {
                scan.onActivity(activity -> { restored.removePhoto(); restored.close(); });
            }
        }
    }

    private FoodTypeEstimate cookies() {
        return new FoodTypeEstimate("cookies", Collections.singletonList(milk));
    }

    private FoodCrossCheckController create(ScanTestSupport.Scan scan, Bundle saved,
                                           FoodCrossCheckController.ModelFactory factory) {
        AtomicReference<FoodCrossCheckController> controller = new AtomicReference<>();
        scan.onActivity(activity -> controller.set(new FoodCrossCheckController(activity, saved,
                () -> { }, factory)));
        return controller.get();
    }

    private interface Inference {
        FoodTypeEstimate estimate(String image, List<Allergen> selected) throws Exception;
    }

    private static FoodCrossCheckController.FrontPhotoModel model(Inference inference) {
        return new FoodCrossCheckController.FrontPhotoModel() {
            @Override public FoodTypeEstimate estimate(String image, List<Allergen> selected) throws Exception {
                return inference.estimate(image, selected);
            }
            @Override public void close() { }
        };
    }

    private static String message(MainActivity activity) {
        return ((TextView) activity.findViewById(R.id.front_crosscheck_message)).getText().toString();
    }

    private static void awaitMessage(ScanTestSupport.Scan scan, String expected) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            boolean[] ready = {false};
            scan.onActivity(activity -> ready[0] = message(activity).contains(expected));
            if (ready[0]) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        scan.onActivity(activity -> fail("Expected '" + expected + "', saw: " + message(activity)));
    }

    private static void awaitPhoto(ScanTestSupport.Scan scan) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            boolean[] ready = {false};
            scan.onActivity(activity -> ready[0] = ((ImageView) activity.findViewById(
                    R.id.front_photo_preview)).getDrawable() != null);
            if (ready[0]) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The front photo never finished decoding");
    }

    private static void awaitNetworkIdle(FoodCrossCheckController controller) throws Exception {
        Field field = FoodCrossCheckController.class.getDeclaredField("network");
        field.setAccessible(true);
        ExecutorService worker = (ExecutorService) field.get(controller);
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static byte[] serialized(AnalysisReport report) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(report);
        }
        return bytes.toByteArray();
    }
}
