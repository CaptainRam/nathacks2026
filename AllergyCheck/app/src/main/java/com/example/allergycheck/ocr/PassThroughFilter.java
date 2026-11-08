package com.example.allergycheck.ocr;

/** Returns every recognised line untouched. The default, and what you want while debugging OCR. */
public final class PassThroughFilter implements TextFilter {

    @Override
    public OcrResult apply(OcrResult input) {
        return input;
    }
}
