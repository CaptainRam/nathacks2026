package com.example.allergycheck.ocr;

/**
 * Narrows a raw {@link OcrResult} down to the lines that matter.
 *
 * <p>This is the extension point for label-specific logic. {@link OcrPipeline} applies exactly one
 * filter to every recognition result, so swapping {@link PassThroughFilter} for
 * {@link IngredientListFilter} is a one-line change at the call site and nothing else moves.
 *
 * <p>Implementations must be pure: same input, same output, no I/O. That keeps them trivially
 * unit-testable without a device.
 */
public interface TextFilter {

    OcrResult apply(OcrResult input);
}
