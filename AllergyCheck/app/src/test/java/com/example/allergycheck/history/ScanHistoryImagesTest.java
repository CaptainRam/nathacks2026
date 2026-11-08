package com.example.allergycheck.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** File naming and, more importantly, which files {@code maintain()} is allowed to delete. */
public class ScanHistoryImagesTest {

    @Test
    public void nameAndIdRoundTrip() {
        for (long id : new long[]{0L, 1L, 42L, 999_999L, Long.MAX_VALUE}) {
            assertEquals(id, ScanHistoryImages.idFromFileName(ScanHistoryImages.fileNameFor(id)));
        }
    }

    @Test
    public void nameFollowsTheExpectedShape() {
        assertEquals("scan_7.jpg", ScanHistoryImages.fileNameFor(7));
    }

    @Test
    public void namesThatAreNotOursAreRejected() {
        for (String name : new String[]{
                "staging_1.jpg", "scan_.jpg", "scan_abc.jpg", "scan_1.jpg.tmp", "../scan_1.jpg",
                "scan_1.png", "capture_1.jpg", "scan_-1.jpg", "scan_1 .jpg", "", "scan_1",
                "SCAN_1.JPG", "scan_99999999999999999999.jpg"}) {
            assertEquals("should not parse: " + name, -1, ScanHistoryImages.idFromFileName(name));
        }
    }

    @Test
    public void aFileWithNoRowIsAnOrphan() {
        List<String> orphans = ScanHistoryImages.orphanNames(
                Arrays.asList("scan_1.jpg", "scan_2.jpg"), setOf(1L));
        assertEquals(Collections.singletonList("scan_2.jpg"), orphans);
    }

    @Test
    public void filesWithLiveRowsAreKept() {
        assertTrue(ScanHistoryImages.orphanNames(
                Arrays.asList("scan_1.jpg", "scan_2.jpg"), setOf(1L, 2L)).isEmpty());
    }

    @Test
    public void unparseableNamesAreNeverDeleted() {
        // Deleting what we do not understand is how this function would destroy a user's data.
        assertTrue(ScanHistoryImages.orphanNames(
                Arrays.asList("notes.txt", "staging_3.jpg", ".nomedia", "scan_x.jpg"),
                Collections.emptySet()).isEmpty());
    }

    @Test
    public void everyFileIsAnOrphanWhenNoRowsRemain() {
        assertEquals(Arrays.asList("scan_1.jpg", "scan_2.jpg"),
                ScanHistoryImages.orphanNames(
                        Arrays.asList("scan_1.jpg", "scan_2.jpg"), Collections.emptySet()));
    }

    @Test
    public void emptyAndMissingDirectoriesAreHandled() {
        assertTrue(ScanHistoryImages.orphanNames(Collections.emptyList(), setOf(1L)).isEmpty());
        assertTrue(ScanHistoryImages.orphanNames(null, setOf(1L)).isEmpty());
    }

    @Test
    public void nullEntriesAreIgnored() {
        assertEquals(Collections.singletonList("scan_5.jpg"),
                ScanHistoryImages.orphanNames(
                        Arrays.asList(null, "scan_5.jpg"), Collections.emptySet()));
    }

    private static Set<Long> setOf(Long... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }
}
