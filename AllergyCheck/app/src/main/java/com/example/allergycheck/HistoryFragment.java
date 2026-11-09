package com.example.allergycheck;

import android.graphics.Canvas;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.allergycheck.history.ScanHistory;
import com.example.allergycheck.history.ScanHistoryItem;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The list of previous scans, newest first, each opening into the result it produced.
 *
 * <p>Deletes are not written straight through. A swipe takes the row out of the list and holds it
 * here until its Snackbar goes away, so undo is a pure UI operation; re-inserting through the
 * database would mint a new row id, and because image file names derive from the id that would mean
 * copying the photo too, as well as losing the original ordering.
 */
public class HistoryFragment extends Fragment implements ScanHistoryAdapter.Listener {

    private ScanHistory history;
    private ScanHistoryAdapter adapter;
    private RecyclerView list;
    private View emptyState;
    private MaterialButton clearButton;
    private View loadState;
    private View loadProgress;
    private TextView loadTitle;
    private View loadMessage;
    private View retryButton;
    private int pendingRemovalFocusPosition = RecyclerView.NO_POSITION;
    private int pendingRemovalFocusGeneration;
    private int removalFocusGeneration;
    private long initialScanId = RecyclerView.NO_ID;
    private int initialActionId = View.NO_ID;
    private int initialHintGeneration;
    private int initialHintRequest;

    /** Swiped rows awaiting their Snackbar, newest first. Keyed by row id. */
    private final Map<Long, Pending> pendingDeletes = new LinkedHashMap<>();

    /** Bumped before every load so a reload racing a delete cannot resurrect a row. */
    private int loadRequest;

    private static final class Pending {
        final ScanHistoryItem item;
        final int position;

        Pending(ScanHistoryItem item, int position) {
            this.item = item;
            this.position = position;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        history = ScanHistory.get(requireContext());
        adapter = new ScanHistoryAdapter(new HistorySummary(requireContext()), history, this);

        list = view.findViewById(R.id.history_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter.setInitialFocusCandidate(this::shouldOfferScanHint);
        list.setAdapter(adapter);
        new ItemTouchHelper(new SwipeToDelete()).attachToRecyclerView(list);

        emptyState = view.findViewById(R.id.history_empty);
        clearButton = view.findViewById(R.id.clear_history_button);
        loadState = view.findViewById(R.id.history_load_state);
        loadProgress = view.findViewById(R.id.history_loading_progress);
        loadTitle = view.findViewById(R.id.history_load_title);
        loadMessage = view.findViewById(R.id.history_load_message);
        retryButton = view.findViewById(R.id.history_load_retry);
        retryButton.setOnClickListener(button -> {
            cancelPendingRemovalFocus();
            reload();
        });
        clearButton.setOnClickListener(button -> confirmClearAll());
        InitialFocusHint.install(retryButton, () -> shouldOfferActionHint(R.id.history_load_retry));
        InitialFocusHint.install(clearButton, () -> shouldOfferActionHint(R.id.clear_history_button));
        InitialFocusHint.install(view.findViewById(R.id.history_empty_action),
                () -> shouldOfferActionHint(R.id.history_empty_action));
        view.findViewById(R.id.history_empty_action).setOnClickListener(button -> {
            if (getActivity() instanceof HistoryHost) {
                ((HistoryHost) getActivity()).showScanTab();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        refresh();
    }

    /**
     * Reloads the list on demand. The fragment is only created once and then just shown/hidden by
     * {@code MainActivity} on tab switches, so {@link #onStart()} alone would miss a scan finished
     * while the tab was already visited earlier in the session; the host calls this on every
     * switch into the tab to cover that case.
     */
    void refresh() {
        reload();
    }

    /**
     * A Snackbar is dismissed when the fragment stops, so anything still pending has to be applied
     * here. Otherwise a row the user deleted would come back on the next load, which reads as a
     * bug; this makes navigating away behave the same as letting the Snackbar time out.
     */
    @Override
    public void onStop() {
        flushPendingDeletes();
        super.onStop();
    }

    @Override
    public void onPause() {
        cancelPendingRemovalFocus();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        cancelPendingRemovalFocus();
        ++loadRequest;
        adapter.shutDown();
        super.onDestroyView();
    }

    // ----- Loading -----

    private void reload() {
        beginLoad(true);
    }

    private void load() {
        beginLoad(false);
    }

    private void beginLoad(boolean maintain) {
        View page = getView();
        if (page == null) return;
        int request = ++loadRequest;
        showLoadState(false);
        Runnable query = () -> {
            if (!isAdded() || getView() != page || request != loadRequest) return;
            history.loadWithStatus(ScanHistory.DEFAULT_LIMIT, new ScanHistory.LoadCallback() {
                @Override
                public void onLoaded(@NonNull List<ScanHistoryItem> items) {
                    if (!isCurrentLoad(page, request)) return;
                    // Anything swiped but not yet committed must stay out of the list.
                    items.removeIf(item -> pendingDeletes.containsKey(item.id()));
                    adapter.submit(items);
                    declarePendingLoadHint(request);
                    showEmptyState(adapter.isEmpty());
                }

                @Override
                public void onFailure() {
                    if (!isCurrentLoad(page, request)) return;
                    // Keep cached rows in the adapter, but do not expose stale data or destructive
                    // controls while their current state could not be read.
                    declarePendingLoadHint(request, true);
                    showLoadState(true);
                }
            });
        };
        // Prune the retention window before reading, so the list never shows scans the policy has
        // already dropped. Startup alone would miss a session left open past a row's expiry.
        if (maintain) history.maintain(query::run);
        else query.run();
    }

    private boolean isCurrentLoad(View page, int request) {
        return isAdded() && getView() == page && request == loadRequest;
    }

    private void declarePendingLoadHint(int request) {
        declarePendingLoadHint(request, false);
    }

    private void declarePendingLoadHint(int request, boolean failed) {
        if (pendingRemovalFocusPosition == RecyclerView.NO_POSITION) return;
        int position = pendingRemovalFocusPosition;
        int generation = pendingRemovalFocusGeneration;
        pendingRemovalFocusPosition = RecyclerView.NO_POSITION;
        if (!canOfferReadingHint(getView(), generation) || request != loadRequest) return;
        if (failed) offerActionHint(R.id.history_load_retry, generation);
        else if (adapter.isEmpty()) offerActionHint(R.id.history_empty_action, generation);
        else offerScanHint(adapter.itemAt(Math.min(position, adapter.size() - 1)).id(), generation);
    }

    private void showLoadState(boolean failed) {
        list.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        clearButton.setVisibility(View.GONE);
        loadState.setVisibility(View.VISIBLE);
        loadProgress.setVisibility(failed ? View.GONE : View.VISIBLE);
        updateHeader(failed ? R.string.history_load_failed_title : R.string.history_loading);
        loadMessage.setVisibility(failed ? View.VISIBLE : View.GONE);
        retryButton.setVisibility(failed ? View.VISIBLE : View.GONE);
    }

    /** Offer a fallback after an explicit action; TalkBack decides whether it needs one. */
    private void offerScanHint(long id, int generation) {
        if (!canOfferReadingHint(getView(), generation)) return;
        initialScanId = id;
        initialActionId = View.NO_ID;
        initialHintGeneration = generation;
        initialHintRequest = loadRequest;
    }

    private void offerActionHint(int actionId, int generation) {
        if (!canOfferReadingHint(getView(), generation)) return;
        initialScanId = RecyclerView.NO_ID;
        initialActionId = actionId;
        initialHintGeneration = generation;
        initialHintRequest = loadRequest;
    }

    private boolean shouldOfferScanHint(long id) {
        if (id != initialScanId || !isCurrentHint() || !list.isShown()) return false;
        RecyclerView.ViewHolder holder = list.findViewHolderForItemId(id);
        return holder != null && holder.itemView.isShown()
                && !hasOtherReadingPosition(requireView(), holder.itemView);
    }

    private boolean shouldOfferActionHint(int actionId) {
        if (actionId != initialActionId || !isCurrentHint()) return false;
        View target = requireView().findViewById(actionId);
        return target != null && target.isShown()
                && !hasOtherReadingPosition(requireView(), target);
    }

    private boolean isCurrentHint() {
        return initialHintRequest == loadRequest
                && canOfferReadingHint(getView(), initialHintGeneration)
                && requireView().hasWindowFocus();
    }

    private boolean canOfferReadingHint(@Nullable View page, int generation) {
        return page != null && generation == removalFocusGeneration && getView() == page
                && isAdded() && isResumed() && isVisible() && page.isShown();
    }

    private static boolean hasOtherReadingPosition(View view, View candidate) {
        if (!view.isShown()) return false;
        if (view != candidate && view.isAccessibilityFocused()) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (hasOtherReadingPosition(group.getChildAt(index), candidate)) return true;
            }
        }
        return false;
    }

    /** Revoke any old declaration when navigation, a new action or lifecycle changes its origin. */
    void cancelPendingRemovalFocus() {
        ++removalFocusGeneration;
        pendingRemovalFocusPosition = RecyclerView.NO_POSITION;
        initialScanId = RecyclerView.NO_ID;
        initialActionId = View.NO_ID;
    }

    private void updateHeader(int titleRes) {
        String title = getString(titleRes);
        if (!title.contentEquals(loadTitle.getText())) loadTitle.setText(title);
        ViewCompat.setAccessibilityPaneTitle(loadTitle, titleRes == R.string.history_title
                ? title : getString(R.string.a11y_history_state_pane, title));
    }

    private void showEmptyState(boolean empty) {
        loadState.setVisibility(View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        clearButton.setVisibility(empty ? View.GONE : View.VISIBLE);
        updateHeader(R.string.history_title);
    }

    // ----- Rows -----

    @Override
    public void onScanClicked(@NonNull ScanHistoryItem item) {
        cancelPendingRemovalFocus();
        getParentFragmentManager().beginTransaction()
                .replace(R.id.history_container, ScanDetailFragment.of(item.id()))
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void onScanLongPressed(@NonNull ScanHistoryItem item) {
        cancelPendingRemovalFocus();
        final int generation = removalFocusGeneration;
        final View page = getView();
        int itemPosition = 0;
        for (int position = 0; position < adapter.size(); position++) {
            if (adapter.itemAt(position).id() == item.id()) {
                itemPosition = position;
                break;
            }
        }
        final int removedPosition = itemPosition;
        Runnable declareCancelledRowHint = () -> {
            for (int position = 0; position < adapter.size(); position++) {
                if (adapter.itemAt(position).id() == item.id()) {
                    offerScanHint(item.id(), generation);
                    return;
                }
            }
        };
        // The dialog is the confirmation, so this deletes straight away: offering undo after an
        // explicit confirm is just noise.
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.history_delete_one_title)
                .setMessage(R.string.history_delete_one_message)
                .setNegativeButton(R.string.cancel, (dialog, which) -> declareCancelledRowHint.run())
                .setOnCancelListener(dialog -> declareCancelledRowHint.run())
                .setPositiveButton(R.string.history_delete, (dialog, which) ->
                        history.delete(item.id(), () -> {
                            if (isAdded()) {
                                if (canOfferReadingHint(page, generation)) {
                                    pendingRemovalFocusPosition = removedPosition;
                                    pendingRemovalFocusGeneration = generation;
                                }
                                load();
                            }
                        }))
                .show();
    }

    @Override
    public void onScanSwiped(int position, @NonNull ScanHistoryItem item) {
        cancelPendingRemovalFocus();
        final int generation = removalFocusGeneration;
        final View page = getView();
        adapter.removeAt(position);
        pendingDeletes.put(item.id(), new Pending(item, position));
        if (loadState.getVisibility() != View.VISIBLE) showEmptyState(adapter.isEmpty());

        Snackbar.make(requireView(), R.string.history_deleted, Snackbar.LENGTH_LONG)
                .setAction(R.string.history_undo, view -> {
                    Pending restored = pendingDeletes.remove(item.id());
                    if (restored != null) {
                        adapter.insertAt(restored.position, restored.item);
                        if (canOfferReadingHint(page, generation)
                                && loadState.getVisibility() != View.VISIBLE) {
                            offerScanHint(restored.item.id(), generation);
                        }
                        if (loadState.getVisibility() != View.VISIBLE) {
                            showEmptyState(adapter.isEmpty());
                            updateHeader(R.string.history_restored_title);
                        }
                    }
                })
                .addCallback(new Snackbar.Callback() {
                    @Override
                    public void onDismissed(Snackbar snackbar, int event) {
                        if (event != DISMISS_EVENT_ACTION) {
                            commitPendingDelete(item.id());
                        }
                    }
                })
                .show();
    }

    // ----- Deleting -----

    private void commitPendingDelete(long id) {
        if (pendingDeletes.remove(id) == null) {
            return;
        }
        history.delete(id, null);
    }

    private void flushPendingDeletes() {
        if (pendingDeletes.isEmpty()) {
            return;
        }
        for (Long id : pendingDeletes.keySet()) {
            history.delete(id, null);
        }
        pendingDeletes.clear();
    }

    private void confirmClearAll() {
        cancelPendingRemovalFocus();
        final int generation = removalFocusGeneration;
        final View page = getView();
        // Pending swipes are part of what is being cleared; applying them first keeps the count
        // honest and avoids a Snackbar outliving the thing it referred to.
        flushPendingDeletes();
        int count = adapter.size();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.history_clear_confirm_title)
                .setMessage(getResources().getQuantityString(
                        R.plurals.history_clear_confirm_message, count, count))
                .setNegativeButton(R.string.cancel, (dialog, which) ->
                        offerActionHint(R.id.clear_history_button, generation))
                .setOnCancelListener(dialog -> offerActionHint(R.id.clear_history_button, generation))
                .setPositiveButton(R.string.history_clear_confirm_action, (dialog, which) ->
                        history.clear(() -> {
                            if (isAdded()) {
                                if (canOfferReadingHint(page, generation)) {
                                    pendingRemovalFocusPosition = 0;
                                    pendingRemovalFocusGeneration = generation;
                                }
                                load();
                            }
                        }))
                .show();
    }

    /** Swipe either way to remove a row, with the row fading as it goes. */
    private final class SwipeToDelete extends ItemTouchHelper.SimpleCallback {

        SwipeToDelete() {
            super(0, ItemTouchHelper.START | ItemTouchHelper.END);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView view, @NonNull RecyclerView.ViewHolder holder,
                              @NonNull RecyclerView.ViewHolder target) {
            return false;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
            int position = holder.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION) {
                onScanSwiped(position, adapter.itemAt(position));
            }
        }

        @Override
        public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView view,
                                @NonNull RecyclerView.ViewHolder holder, float dX, float dY,
                                int actionState, boolean isCurrentlyActive) {
            float width = Math.max(1f, holder.itemView.getWidth());
            holder.itemView.setAlpha(1f - Math.min(1f, Math.abs(dX) / width));
            super.onChildDraw(canvas, view, holder, dX, dY, actionState, isCurrentlyActive);
        }

        @Override
        public void clearView(@NonNull RecyclerView view,
                              @NonNull RecyclerView.ViewHolder holder) {
            holder.itemView.setAlpha(1f);
            super.clearView(view, holder);
        }
    }
}
