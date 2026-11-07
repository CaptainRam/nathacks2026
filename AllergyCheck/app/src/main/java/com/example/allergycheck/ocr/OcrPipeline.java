package com.example.allergycheck.ocr;

import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;

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

/**
 * Turns an image into label text: recognise, then narrow with a {@link TextFilter}.
 *
 * <p>The only OCR entry point the UI needs. {@link #recognize} accepts any readable image
 * {@link Uri}, so a photo-picker URI and a camera-capture URI are handled identically — adding
 * camera capture later requires no change here.
 *
 * <p>Create one per screen and {@link #close()} it in {@code onDestroy}; the underlying recogniser
 * holds native resources.
 */
public final class OcrPipeline {

    /** Recognition outcome. Both methods are invoked on the main thread. */
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
    }

    private final TextRecognizer recognizer;
    private final TextFilter filter;

    /** Uses {@link PassThroughFilter} — every recognised line is reported. */
    public OcrPipeline() {
        this(new PassThroughFilter());
    }

    public OcrPipeline(@NonNull TextFilter filter) {
        this.filter = filter;
        this.recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    /**
     * Reads {@code imageUri} and recognises Latin-script text on it, off the main thread.
     *
     * <p>{@link InputImage#fromFilePath} resolves {@code content://} URIs and honours the EXIF
     * orientation tag, so a photo taken in portrait does not need rotating by hand.
     */
    @MainThread
    public void recognize(@NonNull Context context, @NonNull Uri imageUri, @NonNull Callback callback) {
        InputImage image;
        try {
            image = InputImage.fromFilePath(context, imageUri);
        } catch (IOException e) {
            // Surfaced through the same path as recognition errors so callers have one failure branch.
            callback.onFailure(e);
            return;
        }

        recognizer.process(image)
                .addOnSuccessListener(text -> callback.onSuccess(filter.apply(toResult(text))))
                .addOnFailureListener(callback::onFailure);
    }

    /** Releases the recogniser. Safe to call more than once. */
    public void close() {
        recognizer.close();
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
