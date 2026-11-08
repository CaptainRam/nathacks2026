package com.example.allergycheck.history;

import java.util.concurrent.TimeUnit;

/**
 * How long a scan stays in history. Entries older than {@link #RETENTION_MILLIS} are pruned, with
 * their saved images, by {@link ScanHistory#maintain}.
 *
 * <p>{@code now} is always a parameter — never read from the clock in here — so the arithmetic is
 * unit-testable and tests can pin the boundary exactly.
 */
public final class ScanRetention {

    public static final long RETENTION_MILLIS = TimeUnit.DAYS.toMillis(7);

    private ScanRetention() {
    }

    /** Rows created strictly before this instant are expired. */
    public static long cutoff(long now) {
        return now - RETENTION_MILLIS;
    }

    /**
     * A row dated in the future is never expired: a clock that has jumped backwards must not be
     * able to delete scans the user just took.
     */
    public static boolean isExpired(long createdAt, long now) {
        return createdAt < cutoff(now);
    }
}
