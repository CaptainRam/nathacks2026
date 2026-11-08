package com.example.allergycheck;

import android.content.Context;
import android.content.res.Resources;
import android.text.format.DateFormat;

import androidx.annotation.NonNull;

import com.example.allergycheck.history.AllergenNameList;
import com.example.allergycheck.history.ScanHistoryItem;
import com.example.allergycheck.history.ScanSummaryShape;

import java.util.Calendar;

/**
 * Words a history row: the quick result and its timestamp.
 *
 * <p>Lives in this package rather than alongside the rest of {@code history} so it can reuse
 * {@link AnalysisMessage}'s wording, which is package-private. An unreadable label and a scan with
 * no allergens selected deliberately reuse the result page's own headlines, so a saved row and a
 * live result can never word the same verdict differently. The remaining shapes need the allergen
 * names substituted into something shorter than the result page's full sentence.
 */
final class HistorySummary {

    private final Context context;
    private final Resources resources;

    HistorySummary(@NonNull Context context) {
        this.context = context;
        this.resources = context.getResources();
    }

    /** The one-line quick result, e.g. "Milk, Soy, and Wheat detected". */
    @NonNull
    String line(@NonNull ScanHistoryItem item) {
        // A scan that never produced a report carries the headline it showed at the time.
        if (item.isFailure() && item.messageTitle() != null) {
            return item.messageTitle();
        }
        ScanSummaryShape shape = ScanSummaryShape.of(item.status());
        switch (shape) {
            case NONE_DETECTED:
                return resources.getString(R.string.history_summary_none_detected);
            case DETECTED:
                return withNames(item, R.string.history_summary_detected, shape);
            case MAY_CONTAIN:
                return withNames(item, R.string.history_summary_may_contain, shape);
            case POSSIBLE:
                return withNames(item, R.string.history_summary_possible, shape);
            case NO_SELECTION:
                return resources.getString(R.string.status_no_allergens_title);
            default:
                return resources.getString(R.string.status_unreadable_title);
        }
    }

    /**
     * Fills a template with the allergen names. Falls back to the result page's own headline if the
     * names are somehow missing, so a row can never read "detected" with nothing in front of it.
     */
    @NonNull
    private String withNames(@NonNull ScanHistoryItem item, int template,
                             @NonNull ScanSummaryShape shape) {
        String names = AllergenNameList.format(item.allergenLabels(),
                resources.getString(R.string.history_name_list_and));
        if (names.isEmpty()) {
            return fallbackTitle(shape);
        }
        return resources.getString(template, names);
    }

    @NonNull
    private String fallbackTitle(@NonNull ScanSummaryShape shape) {
        switch (shape) {
            case DETECTED:
                return resources.getString(R.string.status_contains_title);
            case MAY_CONTAIN:
                return resources.getString(R.string.status_may_contain_title);
            case POSSIBLE:
                return resources.getString(R.string.status_possible_title);
            default:
                return resources.getString(R.string.status_unreadable_title);
        }
    }

    /** "Today, 14:32", "Yesterday, 09:10", or "4 Nov, 18:05". */
    @NonNull
    String timestamp(long createdAt) {
        String time = DateFormat.getTimeFormat(context).format(new java.util.Date(createdAt));
        return timestamp(createdAt, System.currentTimeMillis(), time);
    }

    /** Split out so the day bucketing can be reasoned about without reading the clock. */
    @NonNull
    String timestamp(long createdAt, long now, @NonNull String formattedTime) {
        int days = daysBetween(createdAt, now);
        if (days == 0) {
            return resources.getString(R.string.history_timestamp_today, formattedTime);
        }
        if (days == 1) {
            return resources.getString(R.string.history_timestamp_yesterday, formattedTime);
        }
        String date = DateFormat.format("d MMM", createdAt).toString();
        return resources.getString(R.string.history_timestamp_other, date, formattedTime);
    }

    /** Whole calendar days apart, so "yesterday" means yesterday rather than 24 hours ago. */
    private static int daysBetween(long earlier, long later) {
        Calendar from = midnight(earlier);
        Calendar to = midnight(later);
        long millis = to.getTimeInMillis() - from.getTimeInMillis();
        return (int) (millis / (24L * 60 * 60 * 1000));
    }

    private static Calendar midnight(long time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    /** What TalkBack reads for a row: the time, the result, and the caveat when there is one. */
    @NonNull
    String contentDescription(@NonNull ScanHistoryItem item) {
        String when = timestamp(item.createdAt());
        String what = line(item);
        return item.lowConfidence()
                ? resources.getString(R.string.history_row_description_low_confidence, when, what)
                : resources.getString(R.string.history_row_description, when, what);
    }
}
