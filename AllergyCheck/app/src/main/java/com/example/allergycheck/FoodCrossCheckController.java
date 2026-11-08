package com.example.allergycheck;

import android.content.ActivityNotFoundException;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.crosscheck.FoodTypeCrossCheck;
import com.example.allergycheck.crosscheck.FoodTypeEstimate;
import com.example.allergycheck.crosscheck.OpenRouterFoodTypeModel;
import com.example.allergycheck.crosscheck.UnknownFoodTypeException;
import com.google.android.material.button.MaterialButton;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Optional photo advice, deliberately kept outside the label report and scan-history writer. */
final class FoodCrossCheckController implements AutoCloseable {
    interface FrontPhotoModel extends AutoCloseable {
        FoodTypeEstimate estimate(String imageDataUrl, List<Allergen> allergens) throws Exception;
        @Override void close();
    }

    interface ModelFactory {
        FrontPhotoModel create(String apiKey, String modelId) throws Exception;
    }

    private static final String STATE = "food_crosscheck";
    private static final int MAX_EDGE = 1280;
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final long TIMEOUT_MS = 90_000;
    private final AppCompatActivity activity;
    private final ModelFactory models;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final View panel;
    private final ImageView preview;
    private final MaterialButton add;
    private final View photoCard;
    private final View emptyPhoto;
    private final View photoLoading;
    private final View photoLabel;
    private final View remove;
    private final MaterialButton run;
    private final View settings;
    private final View progress;
    private final TextView message;
    private final ActivityResultLauncher<PickVisualMediaRequest> picker;
    private final ActivityResultLauncher<Uri> capture;
    private String labelPhoto;
    private Uri photo;
    private Uri pendingPhoto;
    private Uri pendingCapture;
    private AnalysisReport report;
    private FoodTypeEstimate estimate;
    private ArrayList<String> estimateAllergens;
    private String estimateModel;
    private Job current;
    private int imageRequest;
    private int status = R.string.crosscheck_awaiting_analysis;
    private boolean loading;
    private boolean visible;
    private boolean closed;
    private boolean suspended;
    private PopupMenu photoMenu;
    private Runnable onPhotoChanged;

    FoodCrossCheckController(AppCompatActivity activity, Bundle state, Runnable openSettings) {
        this(activity, state, openSettings, (key, model) -> {
            OpenRouterFoodTypeModel client = new OpenRouterFoodTypeModel(key, model);
            return new FrontPhotoModel() {
                @Override public FoodTypeEstimate estimate(String image, List<Allergen> allergens)
                        throws Exception {
                    return client.estimate(image, allergens);
                }
                @Override public void close() { client.close(); }
            };
        }, true);
    }

    FoodCrossCheckController(AppCompatActivity activity, Bundle state, Runnable openSettings,
                             ModelFactory models) {
        this(activity, state, openSettings, models, false);
    }

    private FoodCrossCheckController(AppCompatActivity activity, Bundle state, Runnable openSettings,
                                     ModelFactory models, boolean lifecycleRegistration) {
        this.activity = activity;
        this.models = models;
        panel = activity.findViewById(R.id.food_crosscheck_panel);
        preview = activity.findViewById(R.id.front_photo_preview);
        add = activity.findViewById(R.id.front_photo_add);
        photoCard = activity.findViewById(R.id.front_photo_card);
        emptyPhoto = activity.findViewById(R.id.front_photo_empty);
        photoLoading = activity.findViewById(R.id.front_photo_loading);
        photoLabel = activity.findViewById(R.id.front_photo_label);
        remove = activity.findViewById(R.id.front_photo_remove);
        run = panel.findViewById(R.id.front_crosscheck_run);
        settings = panel.findViewById(R.id.front_crosscheck_settings);
        progress = panel.findViewById(R.id.front_crosscheck_progress);
        message = panel.findViewById(R.id.front_crosscheck_message);
        androidx.activity.result.ActivityResultCallback<Uri> onPick =
                uri -> { if (uri != null && labelPhoto != null) loadPhoto(uri); };
        androidx.activity.result.ActivityResultCallback<Boolean> onCapture =
                success -> {
                    Uri captured = pendingCapture;
                    pendingCapture = null;
                    if (captured != null && success && labelPhoto != null) {
                        loadPhoto(captured);
                    } else {
                        deleteOwned(captured);
                        render();
                    }
                };
        if (lifecycleRegistration) {
            picker = activity.registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), onPick);
            capture = activity.registerForActivityResult(new ActivityResultContracts.TakePicture(), onCapture);
        } else {
            String key = "front_test_" + UUID.randomUUID();
            picker = activity.getActivityResultRegistry().register(key + "_pick",
                    new ActivityResultContracts.PickVisualMedia(), onPick);
            capture = activity.getActivityResultRegistry().register(key + "_capture",
                    new ActivityResultContracts.TakePicture(), onCapture);
        }
        add.setOnClickListener(this::showPhotoMenu);
        photoCard.setOnClickListener(this::showPhotoMenu);
        emptyPhoto.setOnClickListener(this::showPhotoMenu);
        preview.setOnClickListener(this::showPhotoMenu);
        remove.setOnClickListener(view -> removePhoto());
        run.setOnClickListener(view -> runCheck());
        settings.setOnClickListener(view -> openSettings.run());
        Bundle saved = state == null ? null : state.getBundle(STATE);
        if (saved != null) {
            labelPhoto = saved.getString("label");
            photo = parse(saved.getString("photo"));
            pendingCapture = parse(saved.getString("capture"));
            estimate = saved.getSerializable("estimate", FoodTypeEstimate.class);
            estimateAllergens = saved.getStringArrayList("allergens");
            estimateModel = saved.getString("model");
            Uri source = parse(saved.getString("pending"));
            if (source == null) source = photo;
            if (source != null && labelPhoto != null) loadPhoto(source, true);
        }
        render();
    }

    void setVisible(boolean visible) {
        this.visible = visible;
        render();
    }

    void setOnPhotoChangedListener(Runnable listener) {
        onPhotoChanged = listener;
    }

    Bitmap currentBitmap() {
        return preview.getDrawable() instanceof BitmapDrawable
                ? ((BitmapDrawable) preview.getDrawable()).getBitmap() : null;
    }

    boolean isPhotoLoading() { return loading; }

    /** Shared by the plus slot and the front-photo edit badge, on either screen. */
    void showPhotoMenu(View anchor) {
        if (closed || loading || labelPhoto == null || pendingCapture != null) return;
        dismissPhotoMenu();
        PopupMenu menu = new PopupMenu(activity, anchor);
        photoMenu = menu;
        menu.getMenu().add(0, 1, 0, R.string.change_photo_choose);
        menu.getMenu().add(0, 2, 1, R.string.change_photo_camera);
        if (photo != null) menu.getMenu().add(0, 3, 2, R.string.crosscheck_remove_photo);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) choosePhoto();
            else if (item.getItemId() == 2) takePhoto();
            else removePhoto();
            return true;
        });
        menu.setOnDismissListener(dismissed -> {
            if (photoMenu == dismissed) photoMenu = null;
        });
        menu.show();
    }

    void dismissPhotoMenu() {
        if (photoMenu != null) {
            PopupMenu menu = photoMenu;
            photoMenu = null;
            menu.dismiss();
        }
    }

    private void notifyPhotoChanged() {
        if (onPhotoChanged != null && !closed) onPhotoChanged.run();
    }

    /** A different label belongs to a different product: its old front photo must be removed. */
    void onLabelPhoto(Uri label) {
        String next = label == null ? null : label.toString();
        if (next == null || !next.equals(labelPhoto)) {
            removePhoto();
            report = null;
        }
        labelPhoto = next;
        render();
    }

    void onLabelAnalysisStarted() {
        cancel();
        report = null;
        status = R.string.crosscheck_awaiting_analysis;
        render();
    }

    void onReport(AnalysisReport report) {
        cancel();
        this.report = report;
        status = R.string.crosscheck_ready;
        maybeCheck();
    }

    void refreshConfiguration() {
        cancel();
        suspended = false;
        status = R.string.crosscheck_ready;
        maybeCheck();
    }

    void suspend() {
        suspended = true;
        cancel();
        status = R.string.crosscheck_ready;
        render();
    }

    private void choosePhoto() {
        try {
            picker.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
        } catch (ActivityNotFoundException exception) {
            status = R.string.crosscheck_photo_error;
            render();
        }
    }

    private void takePhoto() {
        if (labelPhoto == null || pendingCapture != null) return;
        try {
            pendingCapture = uriFor(createPhoto("front_capture_"));
            capture.launch(pendingCapture);
            render();
        } catch (ActivityNotFoundException | IOException | SecurityException
                 | IllegalArgumentException exception) {
            deleteOwned(pendingCapture);
            pendingCapture = null;
            status = R.string.crosscheck_photo_error;
            render();
        }
    }

    void loadPhoto(Uri source) { loadPhoto(source, false); }

    private void loadPhoto(Uri source, boolean restoring) {
        if (closed || labelPhoto == null) return;
        cancel();
        if (!restoring) estimate = null;
        int request = ++imageRequest;
        pendingPhoto = source;
        loading = true;
        dismissPhotoMenu();
        render();
        notifyPhotoChanged();
        images.execute(() -> {
            Bitmap bitmap = null;
            File target = null;
            try {
                bitmap = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(activity.getContentResolver(), source),
                        (decoder, info, unused) -> {
                            int edge = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                            if (edge > MAX_EDGE) {
                                float scale = (float) MAX_EDGE / edge;
                                decoder.setTargetSize(Math.max(1, Math.round(info.getSize().getWidth() * scale)),
                                        Math.max(1, Math.round(info.getSize().getHeight() * scale)));
                            }
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        });
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, encoded)
                        || encoded.size() > MAX_IMAGE_BYTES) throw new IOException("Invalid front photo");
                target = createPhoto("front_photo_");
                try (FileOutputStream output = new FileOutputStream(target)) {
                    encoded.writeTo(output);
                }
                Bitmap decoded = bitmap;
                File completed = target;
                main.post(() -> {
                    if (closed || request != imageRequest) {
                        decoded.recycle();
                        completed.delete();
                        return;
                    }
                    Uri previous = photo;
                    photo = uriFor(completed);
                    pendingPhoto = null;
                    loading = false;
                    preview.setImageBitmap(decoded);
                    deleteOwned(previous);
                    if (!source.equals(previous)) deleteOwned(source);
                    status = R.string.crosscheck_ready;
                    notifyPhotoChanged();
                    maybeCheck();
                });
            } catch (IOException | RuntimeException | OutOfMemoryError failure) {
                if (bitmap != null) bitmap.recycle();
                if (target != null) target.delete();
                main.post(() -> {
                    if (closed || request != imageRequest) return;
                    pendingPhoto = null;
                    loading = false;
                    if (source.equals(photo)) {
                        deleteOwned(photo);
                        photo = null;
                        estimate = null;
                        preview.setImageDrawable(null);
                    } else {
                        deleteOwned(source);
                    }
                    status = R.string.crosscheck_photo_error;
                    render();
                    notifyPhotoChanged();
                });
            }
        });
    }

    void removePhoto() {
        dismissPhotoMenu();
        ++imageRequest;
        cancel();
        deleteOwned(photo);
        if (pendingPhoto != null && !pendingPhoto.equals(photo)) deleteOwned(pendingPhoto);
        deleteOwned(pendingCapture);
        photo = pendingPhoto = pendingCapture = null;
        loading = false;
        estimate = null;
        preview.setImageDrawable(null);
        status = R.string.crosscheck_awaiting_analysis;
        render();
        notifyPhotoChanged();
    }

    private List<Allergen> selectedAllergens() {
        List<Allergen> selected = new ArrayList<>();
        if (report != null) {
            for (AllergenResult result : report.results()) selected.add(result.allergen());
        }
        selected.sort(Comparator.comparing(Allergen::id));
        return selected;
    }

    private ArrayList<String> allergenIds() {
        ArrayList<String> ids = new ArrayList<>();
        for (Allergen allergen : selectedAllergens()) ids.add(allergen.id());
        return ids;
    }

    private boolean usableReport() {
        return report != null && report.overall() != AnalysisReport.Status.UNREADABLE
                && report.overall() != AnalysisReport.Status.NO_ALLERGENS_SELECTED
                && !report.label().ingredients().isEmpty() && !report.results().isEmpty();
    }

    private boolean configured() {
        return !AnalysisPreferences.openRouterApiKey(activity).trim().isEmpty()
                && !AnalysisPreferences.foodTypeModel(activity).trim().isEmpty();
    }

    private void maybeCheck() {
        if (!suspended && photo != null && !loading && usableReport() && configured()) {
            if (estimate == null || !allergenIds().equals(estimateAllergens)
                    || !AnalysisPreferences.foodTypeModel(activity).equals(estimateModel)) {
                runCheck();
                return;
            }
        }
        render();
    }

    void runCheck() {
        if (closed || suspended || loading || photo == null || !usableReport() || !configured()) {
            render();
            return;
        }
        cancel();
        estimate = null;
        Job job = new Job();
        current = job;
        Uri input = photo;
        List<Allergen> selected = selectedAllergens();
        ArrayList<String> ids = allergenIds();
        String model = AnalysisPreferences.foodTypeModel(activity);
        String key = AnalysisPreferences.openRouterApiKey(activity);
        status = R.string.crosscheck_running;
        render();
        main.postDelayed(job.timeout, TIMEOUT_MS);
        job.future = network.submit(() -> {
            try (FrontPhotoModel client = models.create(key, model)) {
                job.model = client;
                if (job.cancelled) return;
                File image = ownedFile(input);
                if (image == null || image.length() > MAX_IMAGE_BYTES) throw new IOException("Invalid front photo");
                byte[] bytes = Files.readAllBytes(image.toPath());
                String data = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                FoodTypeEstimate result = client.estimate(data, selected);
                if (result == null) throw new IOException("Missing food type estimate");
                main.post(() -> {
                    if (closed || current != job || job.cancelled) return;
                    main.removeCallbacks(job.timeout);
                    current = null;
                    estimate = result;
                    estimateAllergens = ids;
                    estimateModel = model;
                    status = R.string.crosscheck_ready;
                    render();
                    message.announceForAccessibility(message.getText());
                });
            } catch (Exception | LinkageError failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                main.post(() -> {
                    if (closed || current != job || job.cancelled) return;
                    cancel();
                    status = failure instanceof UnknownFoodTypeException
                            ? R.string.crosscheck_unknown_food : R.string.crosscheck_error;
                    render();
                });
            }
        });
    }

    private void cancel() {
        Job job = current;
        current = null;
        if (job != null) {
            job.cancelled = true;
            main.removeCallbacks(job.timeout);
            if (job.future != null) job.future.cancel(true);
            if (job.model != null) job.model.close();
        }
    }

    private void render() {
        boolean hasPhoto = photo != null;
        panel.setVisibility(visible && labelPhoto != null
                && (report != null && (hasPhoto || loading)
                || status == R.string.crosscheck_photo_error)
                ? View.VISIBLE : View.GONE);
        boolean busy = loading || current != null;
        photoCard.setEnabled(!closed && !loading && pendingCapture == null);
        photoCard.setContentDescription(activity.getString(hasPhoto
                ? R.string.crosscheck_front_photo_label : R.string.crosscheck_add_photo));
        emptyPhoto.setEnabled(!closed && !loading && pendingCapture == null);
        add.setEnabled(!closed && !loading && pendingCapture == null);
        preview.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        emptyPhoto.setVisibility(hasPhoto ? View.GONE : View.VISIBLE);
        photoLabel.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        photoLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        remove.setVisibility(!hasPhoto && !loading ? View.GONE : View.VISIBLE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        run.setVisibility(photo == null ? View.GONE : View.VISIBLE);
        run.setEnabled(!closed && !suspended && !busy && photo != null && usableReport() && configured());
        run.setText(status == R.string.crosscheck_error || status == R.string.crosscheck_unknown_food
                ? R.string.crosscheck_retry : R.string.crosscheck_run);
        settings.setVisibility(!configured() ? View.VISIBLE : View.GONE);
        if (loading) message.setText(R.string.crosscheck_photo_loading);
        else if (status == R.string.crosscheck_photo_error) message.setText(status);
        else if (!configured()) message.setText(R.string.crosscheck_config_missing);
        else if (report == null) message.setText(R.string.crosscheck_awaiting_analysis);
        else if (report.results().isEmpty()) message.setText(R.string.crosscheck_no_allergens);
        else if (!usableReport()) message.setText(R.string.crosscheck_label_unavailable);
        else if (photo == null) message.setText(R.string.crosscheck_waiting_photo);
        else if (estimate != null) showAdvice();
        else message.setText(status);
    }

    private void showAdvice() {
        FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(report, estimate);
        List<Allergen> missing = advice.missingAllergens();
        if (missing.isEmpty()) {
            message.setText(activity.getString(R.string.crosscheck_no_missing, advice.foodType()));
        } else {
            StringBuilder names = new StringBuilder();
            for (Allergen allergen : missing) {
                if (names.length() > 0) names.append(", ");
                names.append(allergen.label());
            }
            message.setText(activity.getString(R.string.crosscheck_missing_allergens,
                    advice.foodType(), names.toString()));
        }
    }

    void saveState(Bundle out) {
        Bundle saved = new Bundle();
        saved.putString("label", labelPhoto);
        saved.putString("photo", photo == null ? null : photo.toString());
        saved.putString("pending", pendingPhoto == null ? null : pendingPhoto.toString());
        saved.putString("capture", pendingCapture == null ? null : pendingCapture.toString());
        saved.putSerializable("estimate", estimate);
        saved.putStringArrayList("allergens", estimateAllergens);
        saved.putString("model", estimateModel);
        out.putBundle(STATE, saved);
    }

    private File createPhoto(String prefix) throws IOException {
        File directory = new File(activity.getFilesDir(), "photos");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Photo directory unavailable");
        return File.createTempFile(prefix, ".jpg", directory);
    }

    private Uri uriFor(File file) {
        return FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
    }

    /** Only this controller's private copies/captures are ever deleted or uploaded. */
    private File ownedFile(Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())
                || !(activity.getPackageName() + ".fileprovider").equals(uri.getAuthority())
                || uri.getPathSegments().size() != 2 || !"photos".equals(uri.getPathSegments().get(0))) return null;
        String name = uri.getLastPathSegment();
        if (name == null || !(name.startsWith("front_photo_") || name.startsWith("front_capture_"))
                || !name.endsWith(".jpg")) return null;
        File directory = new File(activity.getFilesDir(), "photos").getCanonicalFile();
        File file = new File(directory, name).getCanonicalFile();
        return directory.equals(file.getParentFile()) ? file : null;
    }

    private void deleteOwned(Uri uri) {
        try {
            File file = ownedFile(uri);
            if (file != null) file.delete();
        } catch (IOException ignored) { }
    }

    private static Uri parse(String uri) { return uri == null ? null : Uri.parse(uri); }

    @Override public void close() {
        closed = true;
        onPhotoChanged = null;
        dismissPhotoMenu();
        cancel();
        ++imageRequest;
        images.shutdownNow();
        network.shutdownNow();
        picker.unregister();
        capture.unregister();
        if (activity.isFinishing()) removePhoto();
    }

    private final class Job {
        volatile boolean cancelled;
        volatile FrontPhotoModel model;
        Future<?> future;
        final Runnable timeout = () -> {
            if (current == this && !closed) {
                cancel();
                status = R.string.crosscheck_error;
                render();
            }
        };
    }
}
