package com.example.allergycheck;

import android.graphics.Canvas;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
        list.setAdapter(adapter);
        new ItemTouchHelper(new SwipeToDelete()).attachToRecyclerView(list);

        emptyState = view.findViewById(R.id.history_empty);
        clearButton = view.findViewById(R.id.clear_history_button);
        clearButton.setOnClickListener(button -> confirmClearAll());
        view.findViewById(R.id.history_empty_action).setOnClickListener(button -> {
            if (getActivity() instanceof HistoryHost) {
                ((HistoryHost) getActivity()).showScanTab();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
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
    public void onDestroyView() {
        adapter.shutDown();
        super.onDestroyView();
    }

    // ----- Loading -----

    private void reload() {
        // Prune the retention window before reading, so the list never shows scans the policy has
        // already dropped. Startup alone would miss a session left open past a row's expiry.
        history.maintain(() -> {
            if (isAdded()) {
                load();
            }
        });
    }

    private void load() {
        int request = ++loadRequest;
        history.load(ScanHistory.DEFAULT_LIMIT, items -> {
            if (!isAdded() || getView() == null || request != loadRequest) {
                return;
            }
            // Anything swiped but not yet committed must stay out of the list.
            items.removeIf(item -> pendingDeletes.containsKey(item.id()));
            adapter.submit(items);
            showEmptyState(adapter.isEmpty());
        });
    }

    private void showEmptyState(boolean empty) {
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        clearButton.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ----- Rows -----

    @Override
    public void onScanClicked(@NonNull ScanHistoryItem item) {
        getParentFragmentManager().beginTransaction()
                .replace(R.id.history_container, ScanDetailFragment.of(item.id()))
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void onScanLongPressed(@NonNull ScanHistoryItem item) {
        // The dialog is the confirmation, so this deletes straight away: offering undo after an
        // explicit confirm is just noise.
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.history_delete_one_title)
                .setMessage(R.string.history_delete_one_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.history_delete, (dialog, which) ->
                        history.delete(item.id(), () -> {
                            if (isAdded()) {
                                load();
                            }
                        }))
                .show();
    }

    @Override
    public void onScanSwiped(int position, @NonNull ScanHistoryItem item) {
        adapter.removeAt(position);
        pendingDeletes.put(item.id(), new Pending(item, position));
        showEmptyState(adapter.isEmpty());

        Snackbar.make(requireView(), R.string.history_deleted, Snackbar.LENGTH_LONG)
                .setAction(R.string.history_undo, view -> {
                    Pending restored = pendingDeletes.remove(item.id());
                    if (restored != null) {
                        adapter.insertAt(restored.position, restored.item);
                        showEmptyState(adapter.isEmpty());
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
        // Pending swipes are part of what is being cleared; applying them first keeps the count
        // honest and avoids a Snackbar outliving the thing it referred to.
        flushPendingDeletes();
        int count = adapter.size();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.history_clear_confirm_title)
                .setMessage(getResources().getQuantityString(
                        R.plurals.history_clear_confirm_message, count, count))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.history_clear_confirm_action, (dialog, which) ->
                        history.clear(() -> {
                            if (isAdded()) {
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
