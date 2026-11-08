package com.example.allergycheck.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.allergycheck.analysis.AnalysisReport;

/**
 * A history row opened for display: the summary, the label text as it was read, and the report if
 * it could still be rebuilt.
 */
public final class ScanHistoryDetail {

    private final ScanHistoryItem item;
    private final AnalysisReport report;
    private final String rawText;
    private final String messageBody;

    ScanHistoryDetail(@NonNull ScanHistoryItem item, @Nullable AnalysisReport report,
                      @NonNull String rawText, @Nullable String messageBody) {
        this.item = item;
        this.report = report;
        this.rawText = rawText;
        this.messageBody = messageBody;
    }

    @NonNull
    public ScanHistoryItem item() {
        return item;
    }

    /**
     * The stored report, or {@code null} for a row that never had one (a failed read) or whose
     * blob this build cannot read. Callers fall back to {@link ScanHistoryItem#messageTitle()} and
     * {@link #messageBody()}, which is the same pair the scan page shows for those scans.
     */
    @Nullable
    public AnalysisReport report() {
        return report;
    }

    /** The OCR text, kept in its own column so it survives a report that cannot be read. */
    @NonNull
    public String rawText() {
        return rawText;
    }

    @Nullable
    public String messageBody() {
        return messageBody;
    }
}
