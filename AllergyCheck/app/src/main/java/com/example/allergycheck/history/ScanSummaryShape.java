package com.example.allergycheck.history;

import androidx.annotation.NonNull;

import com.example.allergycheck.analysis.AnalysisReport;

/**
 * Which one-line shape a history row's quick result takes.
 *
 * <p>Separate from the wording itself so the mapping is total over {@link AnalysisReport.Status}
 * and can be unit-tested without {@code Resources}. A seventh status would fail
 * {@code ScanSummaryShapeTest} rather than quietly fall into a default branch.
 */
public enum ScanSummaryShape {
    /** "No allergens detected" — read fine, nothing selected matched. */
    NONE_DETECTED,
    /** "Milk, Soy, and Wheat detected" — the label names them. */
    DETECTED,
    /** "May contain Milk and Soy" — a precautionary statement names them. */
    MAY_CONTAIN,
    /** "Milk possibly detected — check the label" — ambiguous ingredient or uncertain read. */
    POSSIBLE,
    /** The label could not be read well enough to say anything. Never shown as clear. */
    UNREADABLE,
    /** The user had not chosen any allergens when this scan ran. */
    NO_SELECTION,
    /** The scan never produced a report at all; the row carries its own stored message. */
    FAILED;

    @NonNull
    public static ScanSummaryShape of(@NonNull AnalysisReport.Status status) {
        switch (status) {
            case CONTAINS:
                return DETECTED;
            case MAY_CONTAIN:
                return MAY_CONTAIN;
            case POSSIBLE:
                return POSSIBLE;
            case NOT_DETECTED:
                return NONE_DETECTED;
            case UNREADABLE:
                return UNREADABLE;
            case NO_ALLERGENS_SELECTED:
                return NO_SELECTION;
            default:
                throw new IllegalArgumentException("unhandled status " + status);
        }
    }
}
