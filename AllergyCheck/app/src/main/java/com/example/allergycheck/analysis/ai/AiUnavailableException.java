package com.example.allergycheck.analysis.ai;

import java.io.IOException;

/** The model cannot run at all on this device or setup, so no later input should be attempted. */
public final class AiUnavailableException extends IOException {
    private static final long serialVersionUID = 1L;

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
