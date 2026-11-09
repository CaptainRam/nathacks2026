package com.example.allergycheck.analysis;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Everything the results screen needs: an overall status, a per-allergen breakdown, and the
 * parsed label to show the structured ingredients.
 *
 * <p>{@link Status#NOT_DETECTED} means only "not found in the text that was read". It is never a
 * guarantee of safety, and the UI must word it that way.
 */
public final class AnalysisReport implements Serializable {

    private static final long serialVersionUID = 2L;

    /** Severity, strongest first, so {@code compareTo} orders by how much attention is needed. */
    public enum Status {
        CONTAINS,
        MAY_CONTAIN,
        POSSIBLE,
        NOT_DETECTED,
        /** The label could not be read well enough to say anything. Never shown as "clear". */
        UNREADABLE,
        /** The user has not chosen any allergens to check. */
        NO_ALLERGENS_SELECTED
    }

    /** Sanitized recovery information; never contains credentials or provider response text. */
    public enum AiFailure {
        NONE(0), AUTHENTICATION(7), QUOTA(6), MODEL(5), RATE_LIMIT(4),
        UNAVAILABLE(2), TIMEOUT(3), INVALID_RESPONSE(1);

        final int priority;

        AiFailure(int priority) { this.priority = priority; }
    }

    private final Status overall;
    private final List<AllergenResult> results;
    private final ParsedLabel label;
    private final boolean lowConfidence;
    private final boolean aiAnalysis;
    private final boolean aiIncomplete;
    private final AiFailure aiFailure;
    private final long aiRetryAtMillis;

    AnalysisReport(Status overall, List<AllergenResult> results, ParsedLabel label, boolean lowConfidence) {
        this(overall, results, label, lowConfidence, false, false);
    }

    AnalysisReport(Status overall, List<AllergenResult> results, ParsedLabel label,
                   boolean lowConfidence, boolean aiAnalysis, boolean aiIncomplete) {
        this(overall, results, label, lowConfidence, aiAnalysis, aiIncomplete, AiFailure.NONE, 0);
    }

    AnalysisReport(Status overall, List<AllergenResult> results, ParsedLabel label,
                   boolean lowConfidence, boolean aiAnalysis, boolean aiIncomplete,
                   AiFailure aiFailure, long aiRetryAtMillis) {
        this.overall = overall;
        this.results = Collections.unmodifiableList(results);
        this.label = label;
        this.lowConfidence = lowConfidence;
        this.aiAnalysis = aiAnalysis;
        this.aiIncomplete = aiIncomplete;
        this.aiFailure = aiIncomplete && aiFailure != null ? aiFailure : AiFailure.NONE;
        this.aiRetryAtMillis = aiIncomplete ? Math.max(0, aiRetryAtMillis) : 0;
    }

    /** The most serious status across all selected allergens. */
    public Status overall() {
        return overall;
    }

    /** One entry per selected allergen, most serious first. */
    public List<AllergenResult> results() {
        return results;
    }

    public ParsedLabel label() {
        return label;
    }

    /**
     * True when a NOT_DETECTED (or any) result rests on thin evidence — e.g. no "Ingredients"
     * heading and only a couple of items. The UI should suggest retaking the photo.
     */
    public boolean lowConfidence() {
        return lowConfidence;
    }

    public boolean aiAnalysis() {
        return aiAnalysis;
    }

    /** At least one ingredient/allergen pair was not successfully checked. */
    public boolean aiIncomplete() {
        return aiIncomplete;
    }

    public AiFailure aiFailure() {
        // serialVersionUID stays 2: reports saved before these optional fields deserialize
        // with null/zero defaults and remain readable through the existing history codec.
        return aiFailure == null ? AiFailure.NONE : aiFailure;
    }

    /** Epoch deadline supplied by the service, or zero when it specified no cooldown. */
    public long aiRetryAtMillis() { return aiRetryAtMillis; }

    public long aiRetryAfterMillis() {
        return Math.max(0, aiRetryAtMillis - System.currentTimeMillis());
    }

    /** Attach a safe failure while retaining every completed check and matching finding. */
    public AnalysisReport withAiFailure(AiFailure failure, long retryAtMillis) {
        AiFailure safe = failure == null ? AiFailure.NONE : failure;
        if (aiFailure().priority > safe.priority) safe = aiFailure();
        return new AnalysisReport(overall, results, label, lowConfidence, aiAnalysis, aiIncomplete,
                safe, Math.max(aiRetryAtMillis, retryAtMillis));
    }
}
