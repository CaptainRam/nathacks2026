package com.example.allergycheck.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.example.allergycheck.analysis.AnalysisReport;

import org.junit.Test;

/** Every status must map to a shape, so a new status cannot slip through unworded. */
public class ScanSummaryShapeTest {

    @Test
    public void everyStatusHasAShape() {
        for (AnalysisReport.Status status : AnalysisReport.Status.values()) {
            assertNotNull("no shape for " + status, ScanSummaryShape.of(status));
        }
    }

    @Test
    public void statusesMapToTheExpectedShape() {
        assertEquals(ScanSummaryShape.DETECTED,
                ScanSummaryShape.of(AnalysisReport.Status.CONTAINS));
        assertEquals(ScanSummaryShape.MAY_CONTAIN,
                ScanSummaryShape.of(AnalysisReport.Status.MAY_CONTAIN));
        assertEquals(ScanSummaryShape.POSSIBLE,
                ScanSummaryShape.of(AnalysisReport.Status.POSSIBLE));
        assertEquals(ScanSummaryShape.NONE_DETECTED,
                ScanSummaryShape.of(AnalysisReport.Status.NOT_DETECTED));
        assertEquals(ScanSummaryShape.UNREADABLE,
                ScanSummaryShape.of(AnalysisReport.Status.UNREADABLE));
        assertEquals(ScanSummaryShape.NO_SELECTION,
                ScanSummaryShape.of(AnalysisReport.Status.NO_ALLERGENS_SELECTED));
    }

    @Test
    public void unreadableIsNeverReportedAsClear() {
        // The analysis layer is careful never to word an unreadable label as "not detected"; the
        // history summary must not undo that.
        assertEquals(ScanSummaryShape.UNREADABLE,
                ScanSummaryShape.of(AnalysisReport.Status.UNREADABLE));
    }
}
