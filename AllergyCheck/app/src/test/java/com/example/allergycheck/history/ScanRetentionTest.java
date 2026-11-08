package com.example.allergycheck.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

/** The 7-day window, checked at its boundary. */
public class ScanRetentionTest {

    private static final long NOW = 1_762_600_000_000L;

    @Test
    public void retentionIsSevenDays() {
        assertEquals(TimeUnit.DAYS.toMillis(7), ScanRetention.RETENTION_MILLIS);
    }

    @Test
    public void cutoffIsExactlySevenDaysBack() {
        assertEquals(NOW - TimeUnit.DAYS.toMillis(7), ScanRetention.cutoff(NOW));
    }

    @Test
    public void aRowExactlyOnTheBoundaryIsKept() {
        assertFalse(ScanRetention.isExpired(ScanRetention.cutoff(NOW), NOW));
    }

    @Test
    public void oneMillisecondInsideTheWindowIsKept() {
        assertFalse(ScanRetention.isExpired(ScanRetention.cutoff(NOW) + 1, NOW));
    }

    @Test
    public void oneMillisecondOutsideTheWindowIsExpired() {
        assertTrue(ScanRetention.isExpired(ScanRetention.cutoff(NOW) - 1, NOW));
    }

    @Test
    public void aFreshRowIsKept() {
        assertFalse(ScanRetention.isExpired(NOW, NOW));
    }

    @Test
    public void aRowDatedInTheFutureIsNeverExpired() {
        // A clock that jumped backwards must not be able to delete scans just taken.
        assertFalse(ScanRetention.isExpired(NOW + TimeUnit.DAYS.toMillis(30), NOW));
    }
}
