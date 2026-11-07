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

    private static final long serialVersionUID = 1L;

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

    private final Status overall;
    private final List<AllergenResult> results;
    private final ParsedLabel label;
    private final boolean lowConfidence;

    AnalysisReport(Status overall, List<AllergenResult> results, ParsedLabel label, boolean lowConfidence) {
        this.overall = overall;
        this.results = Collections.unmodifiableList(results);
        this.label = label;
        this.lowConfidence = lowConfidence;
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
}
