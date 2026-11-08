package com.example.allergycheck.analysis.ai;

/** A model replied, but this input's answer was malformed; the model may handle later inputs. */
public final class AiResponseFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    public AiResponseFormatException(String message) {
        super(message);
    }

    public AiResponseFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
