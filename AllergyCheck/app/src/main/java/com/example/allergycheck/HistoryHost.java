package com.example.allergycheck;

/**
 * What the history pages need from whatever is hosting them. Deliberately tiny: it means
 * {@code HistoryFragment} never casts to {@link MainActivity}, so extracting the scan page into
 * its own fragment later does not touch history at all.
 */
interface HistoryHost {

    /** Switches back to the scan tab, e.g. from the empty state's call to action. */
    void showScanTab();
}
