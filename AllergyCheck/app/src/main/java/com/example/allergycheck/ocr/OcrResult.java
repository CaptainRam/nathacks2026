package com.example.allergycheck.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of recognising text on one image: an ordered, immutable list of {@link OcrLine}.
 *
 * <p>This type intentionally exposes nothing from the underlying recognition library, so that
 * {@link TextFilter} implementations and the UI stay independent of which OCR engine produced it.
 */
public final class OcrResult {

    private static final OcrResult EMPTY = new OcrResult(Collections.emptyList());

    private final List<OcrLine> lines;

    private OcrResult(List<OcrLine> lines) {
        this.lines = lines;
    }

    public static OcrResult of(List<OcrLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return EMPTY;
        }
        return new OcrResult(Collections.unmodifiableList(new ArrayList<>(lines)));
    }

    public static OcrResult empty() {
        return EMPTY;
    }

    /** Unmodifiable, in recognition order. */
    public List<OcrLine> lines() {
        return lines;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** All line texts joined by newlines — what you show when no filtering has been applied. */
    public String fullText() {
        StringBuilder out = new StringBuilder();
        for (OcrLine line : lines) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line.text());
        }
        return out.toString();
    }
}
