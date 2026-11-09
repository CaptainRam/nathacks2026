package com.example.allergycheck.ocr;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.example.allergycheck.ingredients.IngredientExtractor;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the live camera preview and reports, several times a second, whether the ingredient list in
 * front of the lens can actually be read.
 *
 * <p>An {@link ImageAnalysis.Analyzer}, so CameraX hands it preview frames on a background thread.
 * Each frame it accepts is recognised ({@link TextRecognizer}), mapped into view coordinates, and
 * judged ({@link FrameReadability}); the {@link Assessor} then receives the verdict on the main
 * thread.
 *
 * <p><b>Why not {@link OcrPipeline}.</b> That class is the right tool for a saved photo and the
 * wrong one here: {@link LabelImagePreparer} walks a full-frame {@code int[]} through a grayscale
 * pass, a histogram, and a contrast stretch. That is worth the cost on a 12-megapixel capture where
 * accuracy is everything, and out of the question four times a second. The live path instead hands
 * ML Kit the camera's own YUV buffer untouched — no bitmap, no copy, no conditioning. Live
 * recognition only ever <em>advises the user</em>; the authoritative read still happens in
 * {@link OcrPipeline} after capture, with all the conditioning intact.
 *
 * <p>Create one per capture screen and {@link #close()} it in {@code onDestroy}: it holds a native
 * recogniser, and like {@link OcrPipeline} it suppresses every callback once closed so a frame
 * already in flight cannot report into a destroyed view.
 */
public final class LiveLabelReader implements ImageAnalysis.Analyzer {

    /** Receives one assessment per analysed frame, on the main thread. */
    public interface Assessor {

        /**
         * @param assessment the verdict for this frame
         * @param cleanRead  whether the list this frame yielded shows no sign of a misread; see
         *                   {@link ReadQuality}. Deliberately separate from the verdict rather than
         *                   folded into it: it is capture <em>policy</em> input, not a readability
         *                   judgement, and it must never gate the capture button
         * @param textBounds bounds of the text the verdict is about, in view coordinates, or
         *                   {@code null} when no text was found
         * @param lineBounds every recognised line, in view coordinates, for drawing
         */
        @MainThread
        void onAssessment(@NonNull FrameReadability.Assessment assessment, boolean cleanRead,
                @Nullable RectF textBounds, @NonNull List<RectF> lineBounds);
    }

    /** Supplies the main-thread view state a frame has to be interpreted against. */
    public interface ViewGeometry {

        /**
         * The fixed window frames are judged against, in view coordinates, or {@code null} before
         * the overlay has been laid out — in which case the frame is discarded.
         *
         * <p>Must not be the overlay's animated lock-on rect; see
         * {@code GuideOverlayView.judgeWindow}.
         */
        @MainThread
        @Nullable
        RectF judgeWindow();

        /**
         * The matrix taking recognised-image coordinates to view coordinates, or {@code null} if the
         * preview is not streaming yet. Normally
         * {@link #recognisedImageToView(Matrix, ImageProxy)} fed with
         * {@code PreviewView.getSensorToViewTransform()}, which may only be read on the main thread.
         */
        @MainThread
        @Nullable
        Matrix recognisedImageToView(@NonNull ImageProxy source);
    }

    /**
     * Minimum gap between the starts of two recognitions.
     *
     * <p>250 ms — four a second. Fast enough that correcting your aim feels immediate, slow enough
     * that the recogniser is idle most of the time. Running every frame would pin a core, heat the
     * phone, and change nothing a user could perceive: nobody re-aims a camera 30 times a second.
     */
    private static final long MIN_INTERVAL_NANOS = 250L * 1_000_000L;

    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Assessor assessor;
    private final ViewGeometry geometry;
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean closed;
    private volatile long lastStartNanos;

    /**
     * @param worker the same executor CameraX runs {@link #analyze} on. Recognition results land
     *               here first so the text parsing happens off the main thread; only the part that
     *               genuinely needs view state is posted on.
     */
    public LiveLabelReader(@NonNull Assessor assessor, @NonNull ViewGeometry geometry,
            @NonNull Executor worker) {
        this.assessor = assessor;
        this.geometry = geometry;
        this.worker = worker;
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        // Every path out of this method must close the proxy exactly once. An unclosed ImageProxy is
        // not the loss of one frame: STRATEGY_KEEP_ONLY_LATEST hands over a small fixed buffer pool,
        // so holding one permanently stalls the whole analysis pipeline and the live feedback
        // silently freezes with no error anywhere.
        if (closed) {
            imageProxy.close();
            return;
        }
        long now = System.nanoTime();
        if (now - lastStartNanos < MIN_INTERVAL_NANOS || !busy.compareAndSet(false, true)) {
            // Throttled, or an earlier frame's recognition is still running. Dropping this frame is
            // always right: the next one is ~33 ms away and strictly more current.
            imageProxy.close();
            return;
        }
        lastStartNanos = now;

        Image image = imageProxy.getImage();
        if (image == null) {
            busy.set(false);
            imageProxy.close();
            return;
        }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        // The analysis executor is owned by the capture screen and shut down in its onDestroy, so a
        // recognition finishing on that boundary can have its dispatch refused. Swallowed here, and
        // only here, because the throw would otherwise surface on an ML Kit worker with nothing
        // above it to catch — and because the one thing that still matters for a frame this late is
        // that its buffer goes back to the pool.
        Executor dispatch = command -> {
            try {
                worker.execute(command);
            } catch (RejectedExecutionException rejected) {
                imageProxy.close();
                busy.set(false);
            }
        };
        try {
            // One addOnCompleteListener, not success + failure listeners: it is the only arrangement
            // where the proxy is closed on exactly one path no matter the outcome. The failure case
            // is not hypothetical — close() cancels any task still pending, so teardown routes
            // through here too, which is the same trap OcrPipeline documents.
            //
            // Dispatched to the analysis executor rather than the default main looper. What follows
            // recognition is not free: signalFor runs IngredientExtractor over the whole OCR text,
            // which is five regex passes plus a paren-depth scan, and it happens four times a second
            // while the overlay is animating at display rate. None of it needs the main thread, so
            // it runs here and only the view-dependent remainder is posted on.
            recognizer.process(InputImage.fromMediaImage(image, rotation))
                    .addOnCompleteListener(dispatch, task -> {
                        boolean handedOn = false;
                        try {
                            if (closed || !task.isSuccessful() || task.getResult() == null) {
                                return;
                            }
                            OcrResult result = MlKitLines.toResult(task.getResult());
                            LiveRead read = interpret(result);
                            // The proxy has to stay open across the hop: the matrix is built from
                            // its rotation and dimensions. Ownership moves to the posted task, which
                            // closes it on every path of its own — and only once the post is known
                            // to have been accepted, so a dead looper still falls through to the
                            // finally below rather than stranding a buffer.
                            handedOn = main.post(() -> {
                                try {
                                    if (!closed) {
                                        publish(result, read, imageProxy);
                                    }
                                } finally {
                                    imageProxy.close();
                                    busy.set(false);
                                }
                            });
                        } finally {
                            if (!handedOn) {
                                imageProxy.close();
                                busy.set(false);
                            }
                        }
                    });
        } catch (RuntimeException exception) {
            // fromMediaImage rejects anything but YUV_420_888/NV21. Treat it as a dropped frame
            // rather than taking down the capture screen over one buffer.
            busy.set(false);
            imageProxy.close();
        }
    }

    /**
     * Releases the recogniser. Safe to call more than once; no {@link Assessor} callback is
     * delivered afterwards, including for a frame already with the recogniser.
     */
    public void close() {
        closed = true;
        recognizer.close();
    }

    /**
     * Maps the recognised lines into view coordinates and reports the verdict.
     *
     * <p>Runs on the main thread — which is where it has to be, since both pieces of view state it
     * needs ({@code PreviewView}'s transform and the overlay's window) may only be read there. That
     * is also the reason {@code signal} arrives precomputed rather than being derived here: it is by
     * far the most expensive part of interpreting a frame and the only part that needs no view state
     * at all, so it is done on the analysis thread before this is posted.
     */
    @MainThread
    private void publish(@NonNull OcrResult result, @NonNull LiveRead read,
            @NonNull ImageProxy imageProxy) {
        RectF window = geometry.judgeWindow();
        if (window == null) {
            return;
        }
        Matrix toView = geometry.recognisedImageToView(imageProxy);
        if (toView == null) {
            return;
        }

        List<FrameReadability.Box> boxes = new ArrayList<>();
        List<RectF> lineBounds = new ArrayList<>();
        List<OcrLine> lines = result.lines();
        for (int index = 0; index < lines.size(); index++) {
            OcrLine line = lines.get(index);
            Rect box = line.boundingBox();
            if (box == null) {
                continue;
            }
            RectF mapped = new RectF(box);
            toView.mapRect(mapped);
            boxes.add(new FrameReadability.Box(mapped.left, mapped.top, mapped.right, mapped.bottom));
            lineBounds.add(mapped);
        }

        FrameReadability.Box windowBox = new FrameReadability.Box(
                window.left, window.top, window.right, window.bottom);
        FrameReadability.Assessment assessment =
                FrameReadability.evaluate(boxes, windowBox, read.signal);
        assessor.onAssessment(assessment, read.cleanRead, toRectF(assessment.textBounds()),
                lineBounds);
    }

    /** What one frame's recognised text amounts to: how far it got, and how well it read. */
    private static final class LiveRead {

        final FrameReadability.TextSignal signal;
        final boolean cleanRead;

        LiveRead(FrameReadability.TextSignal signal, boolean cleanRead) {
            this.signal = signal;
            this.cleanRead = cleanRead;
        }
    }

    /**
     * Decides how close the recognised text is to being a usable ingredient list.
     *
     * <p>{@link IngredientExtractor#extract} is the same parser the post-capture analysis runs, and
     * that is the whole point of using it here: a green frame then means "the real pipeline will
     * find a list in this", not merely "some text is visible". It needs a heading <em>and</em> a
     * splittable list under it, so an empty return with a heading on screen is a real middle state —
     * the heading is framed but the list is not — and gets its own signal.
     *
     * <p>Requiring the heading is not over-strict: without one the post-capture extractor returns
     * nothing and the scan ends at {@code status_unreadable_title}. Going green on a headless panel
     * would promise a result the rest of the app cannot deliver.
     *
     * <p>The extraction is also what {@link ReadQuality} judges, which is why both answers come out
     * of here together: {@code extract} is the expensive part of interpreting a frame and running it
     * twice to ask two questions about the same list would double the cost of the live path.
     */
    private static LiveRead interpret(@NonNull OcrResult result) {
        if (result.isEmpty()) {
            return new LiveRead(FrameReadability.TextSignal.NONE, true);
        }
        List<String> extracted = IngredientExtractor.extract(result.fullText());
        if (!extracted.isEmpty()) {
            return new LiveRead(FrameReadability.TextSignal.LIST_PARSED,
                    ReadQuality.looksClean(extracted));
        }
        // No list yet, so there is no extraction to judge the quality of. Reported clean so the
        // flag never reads as "this frame is garbled" on a frame that simply has no list in it.
        List<OcrLine> lines = result.lines();
        for (int index = 0; index < lines.size(); index++) {
            if (IngredientListFilter.startsWithAny(
                    IngredientListFilter.normalise(lines.get(index).text()),
                    IngredientListFilter.INGREDIENT_HEADINGS)) {
                return new LiveRead(FrameReadability.TextSignal.HEADING_VISIBLE, true);
            }
        }
        return new LiveRead(FrameReadability.TextSignal.TEXT_ONLY, true);
    }

    @Nullable
    private static RectF toRectF(@Nullable FrameReadability.Box box) {
        if (box == null) {
            return null;
        }
        return new RectF(box.left, box.top, box.right, box.bottom);
    }

    /**
     * Builds the matrix that takes recognised-image coordinates to view coordinates, or {@code null}
     * if the preview is not streaming yet.
     *
     * <p>Three coordinate spaces are involved, and it is worth naming them, because getting this
     * wrong puts the lock-on frame somewhere plausible but wrong — mirrored, or rotated about the
     * centre — which is almost impossible to spot by reading the code:
     * <ol>
     *   <li><b>Recognised space.</b> ML Kit was handed a rotation, so it reports boxes on the
     *       <em>upright</em> image, which is not how the buffer is laid out.</li>
     *   <li><b>Buffer space.</b> The sensor's own orientation, which is what
     *       {@code getSensorToBufferTransformMatrix} is expressed in.</li>
     *   <li><b>View space.</b> Where the preview is actually drawn, including the crop its scale
     *       type applies.</li>
     * </ol>
     *
     * <p>Composing the three stable transforms keeps this off the experimental
     * {@code androidx.camera.view.transform} API, whose {@code CoordinateTransform} would do the
     * same job behind an opt-in — and whose {@code PreviewView.getOutputTransform()} asserts the
     * main thread, so it could not be used from an analyser at all.
     *
     * @param sensorToView {@code PreviewView.getSensorToViewTransform()}; null before streaming
     */
    @Nullable
    public static Matrix recognisedImageToView(@Nullable Matrix sensorToView,
            @NonNull ImageProxy imageProxy) {
        if (sensorToView == null) {
            return null;
        }
        Matrix bufferToSensor = new Matrix();
        if (!imageProxy.getImageInfo().getSensorToBufferTransformMatrix().invert(bufferToSensor)) {
            return null;
        }
        Matrix recognisedToView = uprightToBuffer(imageProxy.getImageInfo().getRotationDegrees(),
                imageProxy.getWidth(), imageProxy.getHeight());
        // postConcat applies each stage after the last, so a point travels
        // recognised -> buffer -> sensor -> view, in that order.
        recognisedToView.postConcat(bufferToSensor);
        recognisedToView.postConcat(sensorToView);
        return recognisedToView;
    }

    /**
     * Undoes the rotation ML Kit applied, mapping upright-image coordinates back onto the buffer.
     *
     * <p>{@code rotationDegrees} is how far the buffer must turn <em>clockwise</em> to stand upright,
     * so this is the inverse rotation. Worked through for 90° on a {@code w x h} buffer: the upright
     * image is {@code h x w}, a buffer point lands at {@code (h - y, x)}, so the inverse is
     * {@code (x, y) -> (y, h - x)} — a 90° anticlockwise turn followed by a shift down by the
     * buffer's <em>height</em>. The 90° and 270° cases are mirror images of each other and are very
     * easy to transpose, and no unit test can catch it — {@link android.graphics.Matrix} is a throwing
     * stub on the JVM classpath — so the check is the on-device one: if the brackets land mirrored
     * about the preview, these two branches are the reason.
     *
     * @param bufferWidth  the buffer's width, i.e. {@code ImageProxy.getWidth()}
     * @param bufferHeight the buffer's height, i.e. {@code ImageProxy.getHeight()}
     */
    static Matrix uprightToBuffer(int rotationDegrees, int bufferWidth, int bufferHeight) {
        Matrix matrix = new Matrix();
        switch (((rotationDegrees % 360) + 360) % 360) {
            case 90:
                matrix.setRotate(-90f);
                matrix.postTranslate(0f, bufferHeight);
                break;
            case 180:
                matrix.setRotate(180f);
                matrix.postTranslate(bufferWidth, bufferHeight);
                break;
            case 270:
                matrix.setRotate(90f);
                matrix.postTranslate(bufferWidth, 0f);
                break;
            case 0:
            default:
                break;
        }
        return matrix;
    }
}
