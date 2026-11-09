package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.AnalysisReport;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Exercises the real paired-photo UI without cloud credentials or a simulated model. */
@RunWith(AndroidJUnit4.class)
public class PairedPhotoUiTest {
    private final Map<String, Map<String, ?>> previous = new HashMap<>();
    private Context context;
    private ScanTestSupport support;
    private File portrait;

    @Before
    public void isolatePreferencesAndDisableCloud() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[]{"analysis_preferences", "analysis_credentials", "allergy_preferences"}) {
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
    public void restorePreferences() {
        if (support != null) support.close();
        if (portrait != null) portrait.delete();
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
    public void ingredientPhotoAndOptionalAddCardShareOneRowAndOfferPhotoSources() throws Exception {
        File label = labelPhoto();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            support.loadPhoto(scan, label);
            ScanTestSupport.awaitPhotoReady(scan);
            await(scan, activity -> sameRow(activity.findViewById(R.id.photo_card),
                    activity.findViewById(R.id.front_photo_card)));
            scan.onActivity(activity -> {
                assertHomePair(activity);
                assertNull(((ImageView) activity.findViewById(R.id.front_photo_preview)).getDrawable());
                assertTrue(activity.findViewById(R.id.front_photo_add).isShown());
                saveScreenshot(activity, "home-one-photo-plus.png");
            });
            saveViewportScreenshot("home-one-photo-plus-viewport.png");
            scan.onActivity(activity -> assertTrue(activity.findViewById(R.id.front_photo_add).performClick()));
            awaitPhotoSourceMenu(scan);
            scan.onActivity(activity -> controller(activity).dismissPhotoMenu());
        }
    }

    @Test
    public void loadedFrontPhotoUsesMatchingHomeCardBesideIngredientPhoto() throws Exception {
        File label = labelPhoto();
        File front = portraitPhoto();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            support.loadPhoto(scan, label);
            ScanTestSupport.awaitPhotoReady(scan);
            AtomicReference<Object> originalLabel = new AtomicReference<>();
            scan.onActivity(activity -> {
                originalLabel.set(field(activity, "selectedPhoto"));
                controller(activity).loadPhoto(support.photoUri(front));
            });
            awaitFrontPhoto(scan);
            await(scan, activity -> sameRow(activity.findViewById(R.id.photo_card),
                    activity.findViewById(R.id.front_photo_card)));
            scan.onActivity(activity -> {
                assertHomePair(activity);
                assertEquals(originalLabel.get(), field(activity, "selectedPhoto"));
                ImageView frontPreview = activity.findViewById(R.id.front_photo_preview);
                assertTrue(frontPreview.isShown());
                assertEquals(((ImageView) activity.findViewById(R.id.photo_preview)).getScaleType(),
                        frontPreview.getScaleType());
                assertTrue(activity.findViewById(R.id.front_photo_remove).isShown());
                assertFalse(activity.findViewById(R.id.front_crosscheck_run).isEnabled());
                saveScreenshot(activity, "home-two-photos.png");
            });
            saveViewportScreenshot("home-two-photos-viewport.png");
            scan.onActivity(activity -> controller(activity).showPhotoMenu(
                    activity.findViewById(R.id.front_photo_preview)));
            awaitPhotoSourceMenu(scan);
            scan.onActivity(activity -> controller(activity).dismissPhotoMenu());
        }
    }

    @Test
    public void completedAnalysisShowsBothEditablePhotosAboveTheResult() throws Exception {
        File label = labelPhoto();
        File front = portraitPhoto();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            support.loadPhoto(scan, label);
            ScanTestSupport.awaitPhotoReady(scan);
            scan.onActivity(activity -> controller(activity).loadPhoto(support.photoUri(front)));
            awaitFrontPhoto(scan);
            ScanTestSupport.startCheck(scan);
            awaitReport(scan);
            await(scan, activity -> {
                ViewGroup strip = activity.findViewById(R.id.photo_strip);
                return strip.getChildCount() == 2 && sameRow(strip.getChildAt(0), strip.getChildAt(1));
            });
            scan.onActivity(activity -> {
                ViewGroup strip = activity.findViewById(R.id.photo_strip);
                MaterialCardView labelCard = strip.getChildAt(0).findViewById(R.id.thumbnail_card);
                MaterialCardView frontCard = strip.getChildAt(1).findViewById(R.id.thumbnail_card);
                assertNotNull(labelCard);
                assertNotNull(frontCard);
                assertSameCardStyle(labelCard, frontCard);
                assertEquals(labelCard.getHeight(), frontCard.getHeight());
                assertEquals(labelCard.getWidth(), frontCard.getWidth());
                assertTrue(labelCard.isClickable());
                assertTrue(frontCard.isClickable());
                assertNotNull(((ImageView) labelCard.findViewById(R.id.thumbnail_image)).getDrawable());
                assertNotNull(((ImageView) frontCard.findViewById(R.id.thumbnail_image)).getDrawable());
                assertTrue("Photo row must remain above the result", bottomOnScreen(strip)
                        <= topOnScreen(activity.findViewById(R.id.analysis_result)));
                assertFalse("The home input row should not duplicate the result thumbnails",
                        activity.findViewById(R.id.photo_input_row).isShown());
                saveScreenshot(activity, "result-two-photos.png");
            });
            saveViewportScreenshot("result-two-photos-viewport.png");
            scan.onActivity(activity -> {
                ViewGroup strip = activity.findViewById(R.id.photo_strip);
                assertTrue(strip.getChildAt(1).findViewById(R.id.thumbnail_card).performClick());
            });
            awaitPhotoSourceMenu(scan);
            scan.onActivity(activity -> controller(activity).dismissPhotoMenu());
        }
    }

    @Test
    public void removingFrontPhotoRestoresAddSlotWithoutChangingIngredientResult() throws Exception {
        File label = labelPhoto();
        File front = portraitPhoto();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            support.loadPhoto(scan, label);
            ScanTestSupport.awaitPhotoReady(scan);
            scan.onActivity(activity -> controller(activity).loadPhoto(support.photoUri(front)));
            awaitFrontPhoto(scan);
            ScanTestSupport.startCheck(scan);
            awaitReport(scan);
            AtomicReference<AnalysisReport> before = new AtomicReference<>();
            AtomicReference<Bitmap> labelBitmap = new AtomicReference<>();
            AtomicReference<Object> labelUri = new AtomicReference<>();
            AtomicReference<View> labelTarget = new AtomicReference<>();
            AtomicReference<View> frontTarget = new AtomicReference<>();
            scan.onActivity(activity -> {
                before.set((AnalysisReport) field(controller(activity), "report"));
                ViewGroup strip = activity.findViewById(R.id.photo_strip);
                labelTarget.set(strip.getChildAt(0).findViewById(R.id.thumbnail_card));
                frontTarget.set(strip.getChildAt(1).findViewById(R.id.thumbnail_card));
                labelUri.set(field(activity, "selectedPhoto"));
                labelBitmap.set(((BitmapDrawable) ((ImageView) activity.findViewById(R.id.photo_preview))
                        .getDrawable()).getBitmap());
            });
            byte[] original = serialized(before.get());
            scan.onActivity(activity -> controller(activity).removePhoto());
            await(scan, activity -> activity.findViewById(R.id.front_thumbnail_add_card) != null
                    && activity.findViewById(R.id.front_thumbnail_add_card).isShown());
            scan.onActivity(activity -> {
                ViewGroup strip = activity.findViewById(R.id.photo_strip);
                assertEquals(2, strip.getChildCount());
                assertSame("Ingredient target must survive front-photo removal", labelTarget.get(),
                        strip.getChildAt(0).findViewById(R.id.thumbnail_card));
                assertSame("Front target must survive its transition to Add", frontTarget.get(),
                        strip.getChildAt(1).findViewById(R.id.front_thumbnail_add_card));
                assertEquals(android.widget.Button.class.getName(),
                        frontTarget.get().createAccessibilityNodeInfo().getClassName());
                assertNotNull(strip.getChildAt(0).findViewById(R.id.thumbnail_image));
                assertNull(((ImageView) activity.findViewById(R.id.front_photo_preview)).getDrawable());
                assertSame(before.get(), field(controller(activity), "report"));
                assertEquals(labelUri.get(), field(activity, "selectedPhoto"));
                assertSame(labelBitmap.get(), ((BitmapDrawable) ((ImageView) strip.getChildAt(0)
                        .findViewById(R.id.thumbnail_image)).getDrawable()).getBitmap());
                saveScreenshot(activity, "result-one-photo-plus.png");
            });
            saveViewportScreenshot("result-one-photo-plus-viewport.png");
            assertArrayEquals(original, serialized(before.get()));
        }
    }

    private File labelPhoto() throws Exception {
        return support.createLabel("INGREDIENTS: sugar, milk, salt.", "CONTAINS: MILK.");
    }

    private File portraitPhoto() throws Exception {
        File directory = new File(context.getFilesDir(), "photos");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        portrait = File.createTempFile("paired_front_test_", ".jpg", directory);
        Bitmap bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(233, 241, 226));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(28, 78, 47));
        paint.setTextSize(40);
        canvas.drawText("FOOD FRONT", 30, 100, paint);
        paint.setColor(Color.rgb(190, 133, 64));
        canvas.drawCircle(180, 310, 115, paint);
        try (FileOutputStream output = new FileOutputStream(portrait)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output));
        } finally { bitmap.recycle(); }
        return portrait;
    }

    private static FoodCrossCheckController controller(MainActivity activity) {
        return (FoodCrossCheckController) field(activity, "foodCrossCheck");
    }

    private static Object field(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void awaitFrontPhoto(ScanTestSupport.Scan scan) {
        await(scan, activity -> ((ImageView) activity.findViewById(R.id.front_photo_preview))
                .getDrawable() != null);
    }

    private static void awaitReport(ScanTestSupport.Scan scan) {
        await(scan, activity -> field(controller(activity), "report") != null
                && activity.findViewById(R.id.analysis_result).isShown());
    }

    /** Inspect the actual popup window using supported Android APIs, without input reflection. */
    private static void awaitPhotoSourceMenu(ScanTestSupport.Scan scan) {
        await(scan, activity -> {
            String gallery = activity.getString(R.string.change_photo_choose);
            String camera = activity.getString(R.string.change_photo_camera);
            for (View root : WindowInspector.getGlobalWindowViews()) {
                if (root != activity.getWindow().getDecorView()
                        && hasVisibleText(root, gallery) && hasVisibleText(root, camera)) return true;
            }
            return false;
        });
    }

    private static boolean hasVisibleText(View view, String text) {
        if (!view.isShown()) return false;
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasVisibleText(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    private static void await(ScanTestSupport.Scan scan, Predicate<MainActivity> predicate) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        do {
            boolean[] ready = {false};
            scan.onActivity(activity -> ready[0] = predicate.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The paired-photo UI did not reach the expected state");
    }

    private static void assertHomePair(MainActivity activity) {
        MaterialCardView label = activity.findViewById(R.id.photo_card);
        MaterialCardView front = activity.findViewById(R.id.front_photo_card);
        assertTrue(label.isShown());
        assertTrue(front.isShown());
        assertSame(activity.findViewById(R.id.photo_input_row), label.getParent());
        assertSame(label.getParent(), front.getParent());
        assertEquals(label.getHeight(), front.getHeight());
        assertTrue(Math.abs(label.getWidth() - front.getWidth()) <= 1);
        assertTrue(sameRow(label, front));
        assertSameCardStyle(label, front);
    }

    private static void assertSameCardStyle(MaterialCardView first, MaterialCardView second) {
        assertEquals(first.getRadius(), second.getRadius(), 0.1f);
        assertEquals(first.getStrokeWidth(), second.getStrokeWidth());
        assertEquals(first.getCardElevation(), second.getCardElevation(), 0.1f);
        assertEquals(first.getCardBackgroundColor().getDefaultColor(),
                second.getCardBackgroundColor().getDefaultColor());
    }

    private static boolean sameRow(View first, View second) {
        if (!first.isShown() || !second.isShown() || first.getWidth() == 0
                || second.getWidth() == 0 || first.getHeight() == 0 || second.getHeight() == 0) return false;
        int[] one = new int[2];
        int[] two = new int[2];
        first.getLocationOnScreen(one);
        second.getLocationOnScreen(two);
        return Math.abs(one[1] - two[1]) <= 2 && one[0] + first.getWidth() <= two[0];
    }

    private static int topOnScreen(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[1];
    }

    private static int bottomOnScreen(View view) { return topOnScreen(view) + view.getHeight(); }

    private static byte[] serialized(AnalysisReport report) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(report); }
        return bytes.toByteArray();
    }

    /** Captures the composed native window, including hardware card-outline clipping. */
    private void saveViewportScreenshot(String filename) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("The native viewport screenshot was unavailable", bitmap);
        File directory = new File(context.getExternalFilesDir(null), "paired-photo-ui");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private static void saveScreenshot(MainActivity activity, String filename) {
        View content = activity.findViewById(R.id.content_column);
        if (content.getWidth() <= 0 || content.getHeight() <= 0) throw new AssertionError("No layout to capture");
        File directory = new File(activity.getExternalFilesDir(null), "paired-photo-ui");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(MaterialColors.getColor(content, com.google.android.material.R.attr.colorSurface));
        content.draw(canvas);
        try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (Exception failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }
}
