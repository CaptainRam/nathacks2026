package com.example.allergycheck;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.allergycheck.ingredients.LocalModelManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.util.function.BooleanSupplier;

/** Shared missing-model/download entry point for both launcher screens. */
final class ModelDownloadController {
    private final AppCompatActivity activity;
    /** Opens Settings on the model section through the host's result launcher. */
    private final Runnable openModelSettings;
    private final BooleanSupplier modelNeeded;
    private final LocalModelManager manager;
    private final View card;
    private final TextView title;
    private final TextView message;
    private final MaterialButton action;
    private final LinearProgressIndicator progress;
    private final LocalModelManager.Listener listener = this::render;
    private AlertDialog introduction;
    private LocalModelManager.State lastRenderedState;
    private Snackbar statusNotice;

    ModelDownloadController(AppCompatActivity activity, Runnable openModelSettings) {
        this(activity, openModelSettings, () -> true);
    }

    ModelDownloadController(AppCompatActivity activity, Runnable openModelSettings,
                            BooleanSupplier modelNeeded) {
        this.activity = activity;
        this.openModelSettings = openModelSettings;
        this.modelNeeded = modelNeeded;
        manager = LocalModelManager.get(activity);
        card = activity.findViewById(R.id.model_download_card);
        title = activity.findViewById(R.id.model_status_title);
        message = activity.findViewById(R.id.model_status_message);
        action = activity.findViewById(R.id.model_status_action);
        progress = activity.findViewById(R.id.model_status_progress);
        action.setOnClickListener(view -> {
            if (manager.getStatus().isBusy()) {
                openModelSettings.run();
            } else {
                showDownloadPrompt();
            }
        });
    }

    void start() {
        manager.addListener(listener);
    }

    void stop() {
        manager.removeListener(listener);
        if (statusNotice != null) statusNotice.dismiss();
        if (introduction != null) {
            introduction.dismiss();
            introduction = null;
        }
    }

    void offerIntroduction() {
        if (modelNeeded.getAsBoolean() && manager.shouldShowIntroduction()) {
            manager.markIntroductionShown();
            showDownloadPrompt();
        }
    }

    private void showDownloadPrompt() {
        if (!modelNeeded.getAsBoolean() || introduction != null
                || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        SpannableStringBuilder content = new SpannableStringBuilder()
                .append(activity.getText(R.string.model_intro_message)).append("\n\n")
                .append(activity.getText(R.string.model_source_links));
        introduction = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.model_intro_title)
                .setMessage(content)
                .setPositiveButton(R.string.model_download_action, (dialog, which) -> manager.download())
                .setNegativeButton(R.string.model_later, null)
                .create();
        introduction.setOnDismissListener(dialog -> introduction = null);
        introduction.show();
        TextView body = introduction.findViewById(android.R.id.message);
        if (body != null) {
            body.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private void render(LocalModelManager.Status status) {
        LocalModelManager.State previous = lastRenderedState;
        lastRenderedState = status.state;
        if (!modelNeeded.getAsBoolean()) {
            card.setVisibility(View.GONE);
            if (introduction != null) {
                introduction.dismiss();
                introduction = null;
            }
            return;
        }
        boolean wasShown = card.isShown();
        card.setVisibility(status.isInstalled() ? View.GONE : View.VISIBLE);
        // READY hides the card, so announce that transition through a visible live region.
        // Suppress launch snapshots, repeated polls and updates from a hidden screen section.
        if (previous != null && previous != status.state
                && ((status.state == LocalModelManager.State.READY && wasShown)
                || (status.state == LocalModelManager.State.DOWNLOADING && card.isShown()))) {
            if (statusNotice != null) statusNotice.dismiss();
            statusNotice = Snackbar.make(activity.findViewById(android.R.id.content),
                    status.state == LocalModelManager.State.READY
                            ? R.string.model_ready_status : R.string.model_download_notification_description,
                    Snackbar.LENGTH_LONG);
            statusNotice.show();
        }
        setTextIfChanged(title, activity.getText(status.state == LocalModelManager.State.MISSING
                || status.state == LocalModelManager.State.FAILED
                ? R.string.model_missing_title : R.string.model_download_notification_title));
        // Keep current progress readable on demand; do not announce every download poll.
        message.setAccessibilityLiveRegion(status.state == LocalModelManager.State.DOWNLOADING
                ? View.ACCESSIBILITY_LIVE_REGION_NONE : View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress.setVisibility(status.isBusy() ? View.VISIBLE : View.GONE);
        boolean indeterminate = status.state != LocalModelManager.State.DOWNLOADING || status.totalBytes <= 0;
        // Material progress indicators require hiding before switching indeterminate mode.
        if (progress.isIndeterminate() != indeterminate) {
            progress.hide();
            progress.setIndeterminate(indeterminate);
            if (status.isBusy()) progress.show();
        }
        switch (status.state) {
            case DOWNLOADING:
                int percent = status.totalBytes <= 0 ? 0
                        : (int) Math.min(100, status.downloadedBytes * 100 / status.totalBytes);
                progress.setProgressCompat(percent, true);
                setTextIfChanged(message, activity.getString(R.string.model_downloading_status, percent));
                break;
            case PAUSED:
                setTextIfChanged(message, status.error == null
                        ? activity.getString(R.string.model_download_paused_status) : status.error);
                break;
            case INSTALLING:
                setTextIfChanged(message, activity.getText(R.string.model_installing_status));
                break;
            case FAILED:
                setTextIfChanged(message, status.error == null
                        ? activity.getString(R.string.model_download_failed) : status.error);
                break;
            case READY:
                setTextIfChanged(message, activity.getText(R.string.model_ready_status));
                break;
            default:
                setTextIfChanged(message, activity.getText(R.string.model_missing_description));
                break;
        }
        action.setText(status.isBusy() ? R.string.model_manage_action : R.string.model_download_action);
    }

    private static void setTextIfChanged(TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }
}
