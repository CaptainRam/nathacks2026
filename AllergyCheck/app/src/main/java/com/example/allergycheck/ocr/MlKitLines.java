package com.example.allergycheck.ocr;

import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens ML Kit's block/line tree into our engine-independent {@link OcrResult}.
 *
 * <p>Shared by the two paths that run a recogniser: {@link OcrPipeline}, which reads a saved photo,
 * and the capture screen's live frame analyser. Keeping one copy matters because
 * {@link OcrLine#blockIndex()} and {@link OcrLine#lineIndex()} are positional contracts — a second,
 * subtly different flattening would mean a {@link TextFilter} behaved differently depending on which
 * screen had produced its input.
 */
final class MlKitLines {

    private MlKitLines() {
    }

    /** In recognition order, preserving block/line indices and each line's angle. */
    static OcrResult toResult(Text text) {
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
