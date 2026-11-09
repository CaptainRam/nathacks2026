package com.example.allergycheck;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.history.ScanHistory;
import com.example.allergycheck.history.ScanHistoryItem;
import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongPredicate;

/**
 * The history list.
 *
 * <p>The first {@code RecyclerView.Adapter} in this app, which is a deliberate departure from the
 * inflate-and-addView style used elsewhere: every other list here is bounded (one photo, at most a
 * dozen allergens) while this one is not, each row carries a decoded bitmap, and swipe-to-delete
 * needs {@code ItemTouchHelper}, which only attaches to a {@code RecyclerView}.
 */
final class ScanHistoryAdapter extends RecyclerView.Adapter<ScanHistoryAdapter.Row> {

    /** Bounded so a long history cannot grow the cache without limit. */
    private static final int THUMBNAIL_CACHE_SIZE = 32;

    /** Twice the row's 64dp at xxhdpi; enough to draw sharply, small enough to hold many. */
    private static final int THUMBNAIL_PX = 256;
    private static final Object ACCESSIBILITY_POSITION_PAYLOAD = new Object();

    interface Listener {
        void onScanClicked(@NonNull ScanHistoryItem item);

        void onScanSwiped(int position, @NonNull ScanHistoryItem item);

        void onScanLongPressed(@NonNull ScanHistoryItem item);
    }

    private final List<ScanHistoryItem> items = new ArrayList<>();
    private final HistorySummary summary;
    private final ScanHistory history;
    private final Listener listener;
    private final LruCache<Long, Bitmap> thumbnails = new LruCache<>(THUMBNAIL_CACHE_SIZE);
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private LongPredicate initialFocusCandidate = id -> false;

    ScanHistoryAdapter(@NonNull HistorySummary summary, @NonNull ScanHistory history,
                       @NonNull Listener listener) {
        this.summary = summary;
        this.history = history;
        this.listener = listener;
        setHasStableIds(true);
    }

    void setInitialFocusCandidate(@NonNull LongPredicate requested) {
        initialFocusCandidate = requested;
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    void submit(@NonNull List<ScanHistoryItem> replacement) {
        items.clear();
        items.addAll(replacement);
        // The whole list arrives at once from a single query and is bounded by the retention
        // window, so per-item events would cost more to work out than they save. Swipe removal and
        // undo do use specific events, since those animate.
        notifyDataSetChanged();
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    int size() {
        return items.size();
    }

    @NonNull
    ScanHistoryItem itemAt(int position) {
        return items.get(position);
    }

    /** Takes a row out of the list without touching the database, for swipe-then-undo. */
    @NonNull
    ScanHistoryItem removeAt(int position) {
        ScanHistoryItem removed = items.remove(position);
        notifyItemRemoved(position);
        refreshAccessiblePositions();
        return removed;
    }

    void insertAt(int position, @NonNull ScanHistoryItem item) {
        int at = Math.min(position, items.size());
        items.add(at, item);
        notifyItemInserted(at);
        refreshAccessiblePositions();
    }

    private void refreshAccessiblePositions() {
        if (!items.isEmpty()) {
            // A swipe or undo changes every row's count, including those before the changed row.
            // Rebind just the spoken description so cached photos need no additional work.
            notifyItemRangeChanged(0, items.size(), ACCESSIBILITY_POSITION_PAYLOAD);
        }
    }

    void shutDown() {
        decoder.shutdownNow();
        thumbnails.evictAll();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Row(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_scan_history, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Row holder, int position) {
        holder.bind(items.get(position), position, items.size());
    }

    @Override
    public void onBindViewHolder(@NonNull Row holder, int position,
                                 @NonNull List<Object> payloads) {
        if (holder.boundItem != null && payloads.contains(ACCESSIBILITY_POSITION_PAYLOAD)) {
            holder.bindDescription(items.get(position), position, items.size());
        } else {
            onBindViewHolder(holder, position);
        }
    }

    @Override
    public void onViewRecycled(@NonNull Row holder) {
        holder.boundItem = null;
        holder.thumbnail.setTag(null);
        super.onViewRecycled(holder);
    }

    final class Row extends RecyclerView.ViewHolder {

        private final ImageView thumbnail;
        private final TextView timestamp;
        private final TextView summaryLine;
        private final View severity;
        @Nullable private ScanHistoryItem boundItem;

        Row(@NonNull View view) {
            super(view);
            thumbnail = view.findViewById(R.id.scan_thumbnail);
            timestamp = view.findViewById(R.id.scan_timestamp);
            summaryLine = view.findViewById(R.id.scan_summary);
            severity = view.findViewById(R.id.scan_severity);
            view.setOnClickListener(clicked -> {
                if (boundItem != null) {
                    listener.onScanClicked(boundItem);
                }
            });
            view.setOnLongClickListener(clicked -> deleteBoundItem());
            ViewCompat.replaceAccessibilityAction(view,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    view.getResources().getString(R.string.a11y_history_open), null);
            // A named action makes deletion available without a swipe or a long press. Install
            // it once per holder and resolve its current item when invoked, including after reuse.
            // Register before naming long-click: ViewCompat reuses any action with the same
            // label, which would otherwise replace long-click instead of adding a custom action.
            ViewCompat.addAccessibilityAction(view,
                    view.getResources().getString(R.string.a11y_history_delete),
                    (clicked, arguments) -> deleteBoundItem());
            ViewCompat.replaceAccessibilityAction(view,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                    view.getResources().getString(R.string.a11y_history_delete), null);
            // Resolve the current bound id when queried, including after this holder is reused.
            InitialFocusHint.install(view, () -> boundItem != null
                    && initialFocusCandidate.test(boundItem.id()));
        }

        void bind(@NonNull ScanHistoryItem item, int position, int count) {
            boundItem = item;
            timestamp.setText(summary.timestamp(item.createdAt()));
            summaryLine.setText(summary.line(item));
            severity.setBackgroundTintList(
                    ColorStateList.valueOf(severityColour(item.status())));
            bindDescription(item, position, count);
            bindThumbnail(item);
        }

        private void bindDescription(@NonNull ScanHistoryItem item, int position, int count) {
            itemView.setContentDescription(itemView.getResources().getString(
                    R.string.a11y_history_row, position + 1, count,
                    summary.contentDescription(item)));
        }

        private boolean deleteBoundItem() {
            if (boundItem == null) {
                return false;
            }
            listener.onScanLongPressed(boundItem);
            return true;
        }

        private void bindThumbnail(@NonNull ScanHistoryItem item) {
            String name = item.imageName();
            if (name == null) {
                showPlaceholder();
                return;
            }
            Bitmap cached = thumbnails.get(item.id());
            if (cached != null) {
                showBitmap(cached);
                return;
            }
            showPlaceholder();
            // Tag the row so a holder recycled before the decode lands cannot show the wrong photo.
            long expected = item.id();
            thumbnail.setTag(expected);
            File file = history.imageFile(name);
            decoder.execute(() -> {
                Bitmap decoded = decodeThumbnail(file);
                if (decoded == null) {
                    return;
                }
                thumbnail.post(() -> {
                    thumbnails.put(expected, decoded);
                    Object tag = thumbnail.getTag();
                    if (tag instanceof Long && expected == (Long) tag) {
                        showBitmap(decoded);
                    }
                });
            });
        }

        private void showBitmap(@NonNull Bitmap bitmap) {
            thumbnail.setImageBitmap(bitmap);
            thumbnail.setContentDescription(
                    thumbnail.getResources().getString(R.string.history_thumbnail_description));
        }

        private void showPlaceholder() {
            thumbnail.setImageDrawable(null);
            thumbnail.setContentDescription(
                    thumbnail.getResources().getString(R.string.history_no_image));
        }

        private int severityColour(@NonNull AnalysisReport.Status status) {
            // The same tones the result page uses, so a row and its result agree at a glance.
            switch (status) {
                case CONTAINS:
                    return MaterialColors.getColor(itemView,
                            androidx.appcompat.R.attr.colorError);
                case MAY_CONTAIN:
                    if (AllergenPreferences.mayContainRedWarning(itemView.getContext())) {
                        return MaterialColors.getColor(itemView,
                                androidx.appcompat.R.attr.colorError);
                    }
                case POSSIBLE:
                    return itemView.getContext().getColor(R.color.status_warning_container);
                case NOT_DETECTED:
                    return MaterialColors.getColor(itemView,
                            androidx.appcompat.R.attr.colorPrimary);
                default:
                    return MaterialColors.getColor(itemView,
                            com.google.android.material.R.attr.colorOutline);
            }
        }
    }

    /** Decodes bounded to {@link #THUMBNAIL_PX}; a missing or unreadable file yields null. */
    @Nullable
    private static Bitmap decodeThumbnail(@NonNull File file) {
        if (!file.exists()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = longest > THUMBNAIL_PX ? longest / THUMBNAIL_PX : 1;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }
}
