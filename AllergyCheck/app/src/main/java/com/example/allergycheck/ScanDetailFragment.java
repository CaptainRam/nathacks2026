package com.example.allergycheck;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.example.allergycheck.history.ScanHistory;
import com.example.allergycheck.history.ScanHistoryDetail;

import java.io.File;

/**
 * One saved scan, expanded into the result it produced.
 *
 * <p>Renders through {@link AnalysisResultView}, the same class the scan page uses, so a saved
 * result and a live one cannot drift apart. A row with no report behind it — a scan that failed to
 * read — goes through that class's {@code showMessage}, which is also what the scan page does for
 * those, rather than needing a second kind of detail view.
 */
public class ScanDetailFragment extends Fragment {

    private static final String ARG_ID = "scan_id";

    private ScanHistory history;
    private AnalysisResultView resultView;
    private View resultRoot;

    static ScanDetailFragment of(long id) {
        ScanDetailFragment fragment = new ScanDetailFragment();
        Bundle arguments = new Bundle();
        arguments.putLong(ARG_ID, id);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scan_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        history = ScanHistory.get(requireContext());
        // Scoped to this fragment's own root: the scan page has a copy of these ids too.
        resultRoot = view.findViewById(R.id.history_result);
        resultView = new AnalysisResultView((ViewGroup) resultRoot);
        view.findViewById(R.id.detail_back_button).setOnClickListener(button ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());

        long id = requireArguments().getLong(ARG_ID);
        history.detail(id, detail -> {
            if (!isAdded() || getView() != view) {
                return;
            }
            bind(view, detail);
        });
    }

    private void bind(@NonNull View view, @Nullable ScanHistoryDetail detail) {
        HistorySummary summary = new HistorySummary(requireContext());
        TextView timestamp = view.findViewById(R.id.detail_timestamp);
        // Keep the loading heading's View and accessibility node alive. Updating its label in
        // place preserves a reader on that heading without taking focus from Back or another item.

        if (detail == null) {
            // The row went away under us, e.g. pruned while this was opening.
            timestamp.setText(R.string.history_report_unavailable_title);
            resultRoot.setVisibility(View.VISIBLE);
            resultView.showMessage(getString(R.string.history_report_unavailable_title),
                    getString(R.string.history_report_unavailable_message));
            ViewCompat.setAccessibilityPaneTitle(timestamp, getString(
                    R.string.history_report_unavailable_title));
            return;
        }

        timestamp.setText(summary.timestamp(detail.item().createdAt()));
        resultRoot.setVisibility(View.VISIBLE);

        if (detail.report() != null) {
            resultView.showReport(detail.report(),
                    AllergenPreferences.showNotDetected(requireContext()));
        } else if (detail.item().messageTitle() != null) {
            // A scan that reached the user as a message: show them the same thing again.
            resultView.showMessage(detail.item().messageTitle(),
                    detail.messageBody() == null ? "" : detail.messageBody());
        } else {
            // A report that this build cannot read. The summary and the text are still here.
            resultView.showMessage(getString(R.string.history_report_unavailable_title),
                    getString(R.string.history_report_unavailable_message));
        }

        bindPhoto(view, detail);
        bindRawText(view, detail);
        // Publish the ready state after its content is bound so services announce the saved
        // result when the asynchronous load finishes without moving the user's current focus.
        ViewCompat.setAccessibilityPaneTitle(timestamp, getString(R.string.a11y_history_detail_pane,
                timestamp.getText(), resultView.title()));
    }

    private void bindPhoto(@NonNull View view, @NonNull ScanHistoryDetail detail) {
        ImageView photo = view.findViewById(R.id.detail_photo);
        String name = detail.item().imageName();
        if (name == null) {
            photo.setVisibility(View.GONE);
            return;
        }
        File file = history.imageFile(name);
        if (!file.exists()) {
            photo.setVisibility(View.GONE);
            return;
        }
        // Already a display-sized copy, so this needs no subsampling.
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) {
            photo.setVisibility(View.GONE);
            return;
        }
        photo.setImageBitmap(bitmap);
        photo.setVisibility(View.VISIBLE);
    }

    /**
     * The OCR text. Shown for a failed read or an unreadable report — where it is the only thing
     * that explains the verdict — and otherwise left out, since the result already lists the
     * ingredients in a far more readable form.
     */
    private void bindRawText(@NonNull View view, @NonNull ScanHistoryDetail detail) {
        View title = view.findViewById(R.id.detail_raw_text_title);
        View card = view.findViewById(R.id.detail_raw_text_card);
        TextView text = view.findViewById(R.id.detail_raw_text);

        boolean show = !detail.rawText().trim().isEmpty()
                && (detail.report() == null || detail.item().isFailure());
        title.setVisibility(show ? View.VISIBLE : View.GONE);
        card.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            text.setText(detail.rawText());
        }
    }
}
