package com.example.allergycheck;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import androidx.core.content.FileProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared scaffolding for tests that drive a real scan: a synthetic label photo, the reflective
 * hooks into {@link MainActivity}, and a launched activity to run them against.
 *
 * <p>{@code MainActivityOcrIntegrationTest} still carries its own copies of these. It is a large
 * set of timing-sensitive race tests, so moving it onto this is left for its own change rather
 * than bundled into a feature.
 */
final class ScanTestSupport implements AutoCloseable {

    private final Context context;
    private final List<File> photos = new ArrayList<>();

    ScanTestSupport(Context context) {
        this.context = context;
    }

    /** A white label with black text, big enough for the recogniser to read reliably. */
    File createLabel(String... lines) throws Exception {
        File directory = new File(context.getFilesDir(), "photos");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File photo = File.createTempFile("capture_history_test_", ".jpg", directory);
        photos.add(photo);
        Bitmap bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(54);
        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], 48, 100 + i * 90, paint);
        }
        try (FileOutputStream output = new FileOutputStream(photo)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output));
        } finally {
            bitmap.recycle();
        }
        return photo;
    }

    /** A blank photo: the recogniser finds no text at all in this. */
    File createBlankPhoto() throws Exception {
        return createLabel();
    }

    Uri photoUri(File photo) {
        return FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", photo);
    }

    @Override
    public void close() {
        for (File photo : photos) {
            photo.delete();
        }
    }

    // ----- Driving the activity -----

    static Scan launch(Context context) {
        return Scan.launch(context);
    }

    void loadPhoto(Scan scan, File photo) {
        scan.onActivity(activity ->
                invoke(activity, "loadPhoto", new Class<?>[]{Uri.class}, photoUri(photo)));
    }

    /** Waits for the photo to finish decoding, then taps "Check ingredients". */
    static void startCheck(Scan scan) {
        awaitPhotoReady(scan);
        scan.onActivity(activity ->
                assertTrue(activity.findViewById(R.id.start_analysis_button).performClick()));
    }

    static void awaitPhotoReady(Scan scan) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            boolean[] ready = {false};
            scan.onActivity(activity -> {
                View button = activity.findViewById(R.id.start_analysis_button);
                ready[0] = button.isShown() && button.isEnabled()
                        && ((ImageView) activity.findViewById(R.id.photo_preview))
                        .getDrawable() != null;
            });
            if (ready[0]) {
                return;
            }
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Photo was not ready to check");
    }

    static void invoke(MainActivity activity, String name, Class<?>[] parameterTypes,
                       Object... args) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    /** Matches the platform-monitor lifecycle strategy the other activity tests use. */
    static final class Scan implements AutoCloseable {
        private final Instrumentation instrumentation =
                InstrumentationRegistry.getInstrumentation();
        private MainActivity activity;

        private static Scan launch(Context context) {
            Scan scan = new Scan();
            Intent intent = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            scan.activity = (MainActivity) scan.instrumentation.startActivitySync(intent);
            assertNotNull(scan.activity);
            scan.instrumentation.waitForIdleSync();
            scan.awaitResumed();
            return scan;
        }

        /**
         * Waits until the activity is actually resumed. A previous test's activity can still be
         * finishing when this one launches, and acting on one that has already saved its state
         * fails in ways that have nothing to do with what is being tested.
         */
        private void awaitResumed() {
            long deadline = SystemClock.uptimeMillis() + 10_000;
            do {
                boolean[] resumed = {false};
                onActivity(current -> resumed[0] = !current.isFinishing()
                        && !current.isDestroyed()
                        && !current.getSupportFragmentManager().isStateSaved());
                if (resumed[0]) {
                    return;
                }
                SystemClock.sleep(25);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new AssertionError("The activity never became resumed");
        }

        void onActivity(Consumer<MainActivity> action) {
            instrumentation.runOnMainSync(() -> action.accept(activity));
        }

        @Override
        public void close() {
            onActivity(MainActivity::finish);
            instrumentation.waitForIdleSync();
        }
    }
}
