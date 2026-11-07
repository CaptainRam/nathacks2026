package com.example.allergycheck.ocr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the real recogniser over synthetic ingredient panels and measures how well it reads them.
 *
 * <p>This is where {@link LabelImagePreparer}'s defaults come from. {@link #measuresPreparersOnEveryLabel}
 * prints the Levenshtein distance from the recognised text to the known ground truth for each
 * fixture, which is the only honest way to decide whether a preprocessing step is helping:
 *
 * <pre>
 * ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.example.allergycheck.ocr.OcrPipelineTest
 * adb logcat -d -s OcrPipelineTest
 * </pre>
 *
 * <p>The fixtures are deliberately varied, because no single one of them tells you much: a label can
 * be hard to read because it is small, because it is low in contrast, because it is blurred, or any
 * combination, and the preprocessing steps help those cases very differently. They are
 * {@link Canvas} renders rather than photographs so the ground truth is exact and the suite needs no
 * binary assets — the cost being that they cannot reproduce glare, curvature or print texture.
 *
 * <p>The <em>assertions</em> are deliberately loose. ML Kit's output varies with the model version
 * and the device, so asserting on distances would buy flakiness rather than confidence. What is
 * asserted is the contract: recognition completes, a legible label yields text, and a readable label
 * is never reported as empty. The numbers are logged for a human to compare between runs.
 */
@RunWith(AndroidJUnit4.class)
public class OcrPipelineTest {

    private static final String TAG = "OcrPipelineTest";

    /** Lines of a bilingual label, close to what the panel on a chocolate bar actually says. */
    private static final String[] LABEL_LINES = {
            "INGREDIENTS: SUGAR, COCOA BUTTER,",
            "MILK INGREDIENTS, UNSWEETENED",
            "CHOCOLATE, SOY LECITHIN,",
            "NATURAL VANILLA FLAVOUR.",
            "CONTAINS: MILK, SOY.",
            "MAY CONTAIN: PEANUTS, TREE NUTS,",
            "WHEAT.",
    };

    private static final String GROUND_TRUTH = String.join("\n", LABEL_LINES);

    /** A rendered label, named for what makes it hard to read. */
    private static final class Fixture {
        final String name;
        final Uri uri;

        Fixture(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    private static Fixture clean;
    private static Fixture[] fixtures;

    @BeforeClass
    public static void renderFixtures() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Black on white at a comfortable size: the control. Any preparer should read this.
        clean = new Fixture("clean", write(context, "clean.jpg",
                render(1400, 26f, Color.WHITE, Color.BLACK), 95));

        fixtures = new Fixture[]{
                // Grey-on-grey and small, saved at low quality: several problems at once.
                new Fixture("grey-on-grey, small", write(context, "hard.jpg",
                        render(620, 11f, Color.rgb(150, 150, 150), Color.rgb(105, 105, 110)), 55)),
                // Dark and flat, as if photographed in a shop aisle. Contrast is the only problem.
                new Fixture("dark, flat", write(context, "dim.jpg",
                        render(900, 15f, Color.rgb(96, 94, 90), Color.rgb(64, 62, 60)), 70)),
                // Legible but tiny, which is what forces the decoder to upscale.
                new Fixture("small, good contrast", write(context, "tiny.jpg",
                        render(380, 8f, Color.rgb(235, 235, 235), Color.rgb(60, 60, 60)), 80)),
                // Blur is the only problem: the case sharpening exists for.
                new Fixture("blurred, good contrast", write(context, "soft.jpg",
                        soften(render(900, 15f, Color.rgb(240, 240, 240), Color.rgb(50, 50, 50))), 70)),
                // Blurred and flat together, where sharpening and stretching interact badly.
                new Fixture("blurred and flat", write(context, "softdim.jpg",
                        soften(render(900, 15f, Color.rgb(96, 94, 90), Color.rgb(64, 62, 60))), 70)),
        };
    }

    @Test
    public void readsACleanLabelWithPreparation() throws InterruptedException {
        OcrResult result = recognize(new LabelImagePreparer(), clean.uri);

        assertFalse("a clean label must not come back empty", result.isEmpty());
        String text = result.fullText().toUpperCase();
        assertTrue("expected the ingredients heading in: " + text, text.contains("INGREDIENT"));
        assertTrue("expected the contains statement in: " + text, text.contains("CONTAIN"));
    }

    @Test
    public void readsACleanLabelWithoutPreparation() throws InterruptedException {
        // Guards the other direction: preparation must not become load-bearing for labels that were
        // already legible, or the pass-through comparison stops being a meaningful baseline.
        OcrResult result = recognize(new PassThroughPreparer(), clean.uri);

        assertFalse("a clean label must not come back empty", result.isEmpty());
        assertTrue(result.fullText().toUpperCase().contains("INGREDIENT"));
    }

    @Test
    public void measuresPreparersOnEveryLabel() throws InterruptedException {
        Log.i(TAG, String.format("%-24s %10s %10s", "label", "decoded", "prepared"));
        for (Fixture fixture : fixtures) {
            OcrResult raw = recognize(new PassThroughPreparer(), fixture.uri);
            OcrResult prepared = recognize(new LabelImagePreparer(), fixture.uri);

            Log.i(TAG, String.format("%-24s %10d %10d",
                    fixture.name, distanceToTruth(raw), distanceToTruth(prepared)));
            Log.i(TAG, "  prepared text: " + prepared.fullText().replace('\n', '|'));

            // The retry on an empty read means preparation can never leave us with nothing at all.
            assertTrue("preparation turned a readable label into no text: " + fixture.name,
                    raw.isEmpty() || !prepared.isEmpty());
        }
    }

    /** Edit distance from the recognised text to {@link #GROUND_TRUTH}; lower is better. */
    private static int distanceToTruth(OcrResult result) {
        String actual = result.fullText();
        int[] previous = new int[actual.length() + 1];
        int[] current = new int[actual.length() + 1];
        for (int column = 0; column <= actual.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= GROUND_TRUTH.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= actual.length(); column++) {
                int substitution = GROUND_TRUTH.charAt(row - 1) == actual.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[actual.length()];
    }

    /** Drives {@link OcrPipeline} from the main thread and blocks until the callback fires. */
    private static OcrResult recognize(ImagePreparer preparer, Uri imageUri) throws InterruptedException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<OcrResult> success = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<OcrPipeline> pipeline = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        new Handler(Looper.getMainLooper()).post(() -> {
            pipeline.set(new OcrPipeline(new PassThroughFilter(), preparer));
            pipeline.get().recognize(context, imageUri, new OcrPipeline.Callback() {
                @Override
                public void onSuccess(OcrResult result) {
                    success.set(result);
                    done.countDown();
                }

                @Override
                public void onFailure(Exception error) {
                    failure.set(error);
                    done.countDown();
                }
            });
        });

        assertTrue("recognition did not finish in time", done.await(30, TimeUnit.SECONDS));
        new Handler(Looper.getMainLooper()).post(() -> pipeline.get().close());
        if (failure.get() != null) {
            fail("recognition failed: " + failure.get());
        }
        return success.get();
    }

    /** Draws the label lines onto a bitmap {@code width} px wide at the given text size. */
    private static Bitmap render(int width, float textSizePx, int background, int foreground) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSizePx);
        paint.setColor(foreground);

        float lineHeight = textSizePx * 1.45f;
        float margin = textSizePx * 1.5f;
        int height = Math.round(margin * 2 + lineHeight * LABEL_LINES.length);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(background);
        float baseline = margin + textSizePx;
        for (String line : LABEL_LINES) {
            canvas.drawText(line, margin, baseline, paint);
            baseline += lineHeight;
        }
        return bitmap;
    }

    /** Resamples down and back up — a stand-in for the optical softness of a real close-up photo. */
    private static Bitmap soften(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        Bitmap reduced = Bitmap.createScaledBitmap(
                source, Math.round(width * 0.55f), Math.round(height * 0.55f), /* filter= */ true);
        Bitmap softened = Bitmap.createScaledBitmap(reduced, width, height, /* filter= */ true);
        reduced.recycle();
        source.recycle();
        return softened;
    }

    /** JPEG, because that is what a camera hands us — compression artefacts included. */
    private static Uri write(Context context, String name, Bitmap bitmap, int quality) throws IOException {
        File file = new File(context.getCacheDir(), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        }
        bitmap.recycle();
        return Uri.fromFile(file);
    }
}
