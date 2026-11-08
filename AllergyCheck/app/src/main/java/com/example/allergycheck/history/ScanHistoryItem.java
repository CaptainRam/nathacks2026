package com.example.allergycheck.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.allergycheck.analysis.AnalysisReport;

import java.util.Collections;
import java.util.List;

/**
 * One row as the history list needs it: enough to draw the summary line and the thumbnail, and
 * nothing that would require deserialising the stored report.
 */
public final class ScanHistoryItem {

    private final long id;
    private final long createdAt;
    private final AnalysisReport.Status status;
    private final List<String> allergenLabels;
    private final boolean lowConfidence;
    private final boolean aiIncomplete;
    private final String imageName;
    private final String messageTitle;

    ScanHistoryItem(long id, long createdAt, @NonNull AnalysisReport.Status status,
                    @NonNull List<String> allergenLabels, boolean lowConfidence, boolean aiIncomplete,
                    @Nullable String imageName, @Nullable String messageTitle) {
        this.id = id;
        this.createdAt = createdAt;
        this.status = status;
        this.allergenLabels = Collections.unmodifiableList(allergenLabels);
        this.lowConfidence = lowConfidence;
        this.aiIncomplete = aiIncomplete;
        this.imageName = imageName;
        this.messageTitle = messageTitle;
    }

    public long id() {
        return id;
    }

    public long createdAt() {
        return createdAt;
    }

    public AnalysisReport.Status status() {
        return status;
    }

    /** The allergens that carried {@link #status()}, in report order (most serious first). */
    public List<String> allergenLabels() {
        return allergenLabels;
    }

    public boolean lowConfidence() {
        return lowConfidence;
    }

    /** At least one AI check failed; keep this caveat even when label matches were found. */
    public boolean aiIncomplete() {
        return aiIncomplete;
    }

    /** The saved photo's file name, or {@code null} when the image could not be written. */
    @Nullable
    public String imageName() {
        return imageName;
    }

    /**
     * For a scan that never produced a report, the headline that was shown at the time; otherwise
     * {@code null}. This is what tells the summary line to use the stored wording.
     */
    @Nullable
    public String messageTitle() {
        return messageTitle;
    }

    /** True when there is no report behind this row, only a message. */
    public boolean isFailure() {
        return messageTitle != null;
    }
}
