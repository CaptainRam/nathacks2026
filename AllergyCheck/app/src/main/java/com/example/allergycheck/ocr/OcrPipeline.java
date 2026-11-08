package com.example.allergycheck.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turns an image into label text: decode, condition, recognise, then narrow with a {@link TextFilter}.
 *
 * <p>The only OCR entry point the UI needs. {@link #recognize(Context, Uri, Callback)} accepts any
 * readable image {@link Uri}, so a photo-picker URI and a camera-capture URI are handled
 * identically; {@link #recognize(Bitmap, Callback)} reads a preview the caller has already decoded.
 *
 * <p>Both stages in front of the recogniser are swappable. {@link LabelImageDecoder} normalises the
 * resolution and {@link ImagePreparer} conditions the pixels; since ML Kit's Latin recogniser has no
 * quality settings of its own, those two stages are where recognition accuracy is won or lost.
 *
 * <p>Decoding and preparation run on a background thread — they are far too slow for the main thread
 * on a full-resolution photo — but every {@link Callback} method is still invoked on the main thread.
 *
 * <p>Create one per screen and {@link #close()} it in {@code onDestroy}; the underlying recogniser
 * holds native resources.
 */
public final class OcrPipeline {

    /**
     * Recognition outcome. Both methods are invoked on the main thread.
     *
     * <p>Neither method is ever invoked after {@link #close()}, including for work that was already
     * in flight. A screen can therefore close its pipeline in {@code onDestroy} without guarding its
     * callback against an already-destroyed view.
     */
    public interface Callback {

        /**
         * Recognition succeeded. Note that a successful read can still be
         * {@link OcrResult#isEmpty() empty} — that means "found no text", which is a different
         * thing from a failure and must be surfaced differently.
         */
        @MainThread
        void onSuccess(@NonNull OcrResult result);

        /** The image could not be read or recognition failed. Never report this as "no allergens". */
        @MainThread
        void onFailure(@NonNull Exception error);

        /**
         * The run moved on to {@code stage}, so a screen can show how far along it is. Decoding
         * starts as soon as {@code recognize} is called and is not reported separately. Never
         * invoked after {@link #close()} or once the run has finished.
         */
        @MainThread
        default void onStage(@NonNull Stage stage) {
        }
    }

    /** The steps a run reports through {@link Callback#onStage(Stage)}, in order. */
    public enum Stage {
        /** The image is decoded; it is now being conditioned for the recogniser. */
        PREPARING,
        /** The conditioned image is with the recogniser. */
        RECOGNIZING
    }

    private final TextRecognizer recognizer;
    private final TextFilter filter;
    private final ImagePreparer preparer;
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean closed;

    /** Uses {@link PassThroughFilter} and {@link LabelImagePreparer}. */
    public OcrPipeline() {
        this(new PassThroughFilter());
    }

    public OcrPipeline(@NonNull TextFilter filter) {
        this(filter, new LabelImagePreparer());
    }

    public OcrPipeline(@NonNull TextFilter filter, @NonNull ImagePreparer preparer) {
        this.filter = filter;
        this.preparer = preparer;
        this.recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    /**
     * Reads {@code imageUri} and recognises Latin-script text on it, off the main thread.
     *
     * <p>If the prepared image yields no text at all, recognition is retried once on the unmodified
     * decode before reporting an empty result. Conditioning helps most labels but cannot be proven to
     * help every one, and that retry bounds the cost of being wrong about a particular photo.
     *
     * <p>After {@link #close()} this is a silent no-op: nothing is submitted and no {@link Callback}
     * method is invoked.
     */
    @MainThread
    public void recognize(@NonNull Context context, @NonNull Uri imageUri, @NonNull Callback callback) {
        if (closed) {
            // Checked before submitting: the executor is already shut down, and letting it reject the
            // task would throw RejectedExecutionException out of this call on the main thread.
            return;
        }
        Context appContext = context.getApplicationContext();
        imageExecutor.execute(() -> {
            if (closed) {
                return;
            }
            Bitmap decoded = null;
            Bitmap prepared;
            try {
                decoded = LabelImageDecoder.decode(appContext, imageUri);
                reportStage(Stage.PREPARING, callback);
                prepared = preparer.prepare(decoded);
            } catch (IOException | RuntimeException e) {
                // Surfaced through the same path as recognition errors so callers have one failure branch.
                recycleDecoded(decoded);
                reportFailure(e, callback);
                return;
            } catch (OutOfMemoryError e) {
                // Reported rather than swallowed: silently returning no text here would read as a
                // clean, allergen-free label, which is the one outcome a failure must never produce.
                recycleDecoded(decoded);
                reportFailure(new IOException("Image too large to process", e), callback);
                return;
            }
            Bitmap decodedImage = decoded;
            mainHandler.post(() -> process(decodedImage, prepared, /* ownsDecoded= */ true, callback));
        });
    }

    /**
     * Reads an already decoded, upright preview without decoding the photo again.
     *
     * <p>Conditioning and the retry pass work exactly as on the {@link Uri} path, but {@code bitmap}
     * stays the caller's: it is never recycled here — not even when preparation fails — so a preview
     * still on screen remains safe to draw.
     *
     * <p>After {@link #close()} this is a silent no-op: nothing is submitted and no {@link Callback}
     * method is invoked.
     */
    @MainThread
    public void recognize(@NonNull Bitmap bitmap, @NonNull Callback callback) {
        if (closed) {
            // As on the Uri path: submitting to a shut-down executor would throw, not no-op.
            return;
        }
        imageExecutor.execute(() -> {
            if (closed) {
                return;
            }
            Bitmap prepared;
            try {
                reportStage(Stage.PREPARING, callback);
                prepared = preparer.prepare(bitmap);
            } catch (RuntimeException e) {
                reportFailure(e, callback);
                return;
            } catch (OutOfMemoryError e) {
                reportFailure(new IOException("Image too large to process", e), callback);
                return;
            }
            mainHandler.post(() -> process(bitmap, prepared, /* ownsDecoded= */ false, callback));
        });
    }

    /**
     * Releases the recogniser and the image thread. Safe to call more than once.
     *
     * <p>No {@link Callback} method is invoked after this: later {@code recognize} calls return
     * without submitting anything, and work already in flight finishes by recycling its bitmaps
     * silently. That second half matters because closing the recogniser fails any pending ML Kit
     * task, which would otherwise deliver {@code onFailure} to a screen that is already gone.
     */
    public void close() {
        closed = true;
        imageExecutor.shutdown();
        recognizer.close();
    }

    /** First pass: the conditioned image. */
    @MainThread
    private void process(@NonNull Bitmap decoded, @NonNull Bitmap prepared, boolean ownsDecoded,
            @NonNull Callback callback) {
        if (closed) {
            recycle(decoded, prepared, ownsDecoded);
            return;
        }
        callback.onStage(Stage.RECOGNIZING);
        recognizer.process(InputImage.fromBitmap(prepared, /* rotationDegrees= */ 0))
                .addOnSuccessListener(text -> {
                    if (closed) {
                        recycle(decoded, prepared, ownsDecoded);
                        return;
                    }
                    OcrResult result = filter.apply(toResult(text));
                    if (result.isEmpty() && prepared != decoded) {
                        retryOnDecoded(decoded, prepared, ownsDecoded, callback);
                    } else {
                        recycle(decoded, prepared, ownsDecoded);
                        callback.onSuccess(result);
                    }
                })
                .addOnFailureListener(error -> {
                    // close() fails any task still pending, so this also fires on teardown, where
                    // reporting the error would push a failure onto a destroyed screen.
                    recycle(decoded, prepared, ownsDecoded);
                    if (closed) {
                        return;
                    }
                    callback.onFailure(error);
                });
    }

    /** Second and final pass: the image exactly as decoded, in case conditioning cost us the text. */
    @MainThread
    private void retryOnDecoded(@NonNull Bitmap decoded, @NonNull Bitmap prepared, boolean ownsDecoded,
            @NonNull Callback callback) {
        if (closed) {
            recycle(decoded, prepared, ownsDecoded);
            return;
        }
        recognizer.process(InputImage.fromBitmap(decoded, /* rotationDegrees= */ 0))
                .addOnSuccessListener(text -> {
                    if (closed) {
                        recycle(decoded, prepared, ownsDecoded);
                        return;
                    }
                    OcrResult result = filter.apply(toResult(text));
                    recycle(decoded, prepared, ownsDecoded);
                    callback.onSuccess(result);
                })
                .addOnFailureListener(error -> {
                    // As in process(): a failure here can simply mean close() cancelled the task.
                    recycle(decoded, prepared, ownsDecoded);
                    if (closed) {
                        return;
                    }
                    callback.onFailure(error);
                });
    }

    /** Posts {@code stage} to the main thread, unless the pipeline is closed by the time it runs. */
    private void reportStage(@NonNull Stage stage, @NonNull Callback callback) {
        mainHandler.post(() -> {
            if (!closed) {
                callback.onStage(stage);
            }
        });
    }

    /** Failure delivery must check cancellation when it runs, rather than when it is queued. */
    private void reportFailure(@NonNull Exception error, @NonNull Callback callback) {
        mainHandler.post(() -> {
            if (!closed) {
                callback.onFailure(error);
            }
        });
    }

    /**
     * Called once per {@code recognize} call, after every pass that could read these bitmaps.
     *
     * <p>{@code decoded} is only recycled when this pipeline decoded it; on the {@link Bitmap} entry
     * point it belongs to the caller and may still be on screen.
     */
    private static void recycle(@NonNull Bitmap decoded, @NonNull Bitmap prepared, boolean ownsDecoded) {
        if (prepared != decoded) {
            prepared.recycle();
        }
        if (ownsDecoded) {
            decoded.recycle();
        }
    }

    /**
     * Frees a bitmap this pipeline decoded when a later stage threw before anything took ownership
     * of it; without this, a failed preparation leaks the whole 20-27 MB decode, so the next attempt
     * after an out-of-memory error runs out of memory again.
     *
     * <p>Null-tolerant because the decode itself may be what threw, and only ever called on the
     * {@link Uri} path: on the {@link Bitmap} entry point the image belongs to the caller.
     */
    private static void recycleDecoded(Bitmap decoded) {
        if (decoded != null) {
            decoded.recycle();
        }
    }

    /** Flattens ML Kit's block/line tree into our engine-independent {@link OcrResult}. */
    private static OcrResult toResult(Text text) {
        List<OcrLine> lines = new ArrayList<>();
        List<Text.TextBlock> blocks = text.getTextBlocks();
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            List<Text.Line> blockLines = blocks.get(blockIndex).getLines();
            for (int lineIndex = 0; lineIndex < blockLines.size(); lineIndex++) {
                Text.Line line = blockLines.get(lineIndex);
                Rect box = line.getBoundingBox();
                lines.add(new OcrLine(line.getText(), box, blockIndex, lineIndex, line.getAngle()));
            }
        }
        return OcrResult.of(lines);
    }
}
