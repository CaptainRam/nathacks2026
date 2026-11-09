package com.example.allergycheck;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Surface;
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.TorchState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.allergycheck.ocr.LiveLabelReader;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textview.MaterialTextView;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Short-lived screen that shows a live camera preview with a scanning overlay for aligning an
 * ingredients label, then hands the captured JPEG back to the caller. Kept separate from
 * MainActivity so the capture/permission/lifecycle plumbing for CameraX doesn't touch the main
 * screen's much larger view tree.
 *
 * <p>Alongside the preview it runs a third use case, {@link ImageAnalysis}, feeding
 * {@link LiveLabelReader}. That is what makes the overlay a scanner rather than a viewfinder grid:
 * the frame locks onto the ingredient list, the status chip names whatever is stopping it reading,
 * and the action button stays gated until a frame looks worth analysing. The capture contract is
 * unchanged — the JPEG still goes to {@link #EXTRA_OUTPUT_FILE_PATH} and the result carries nothing
 * but a status.
 */
public class CameraCaptureActivity extends AppCompatActivity {

    public static final String EXTRA_OUTPUT_FILE_PATH = "output_file_path";

    /**
     * Analysis resolution, in the sensor's natural landscape orientation.
     *
     * <p>Chosen deliberately: {@link ImageAnalysis}'s default is 640x480, at which an ingredient
     * line occupying 2.5% of the frame height is about nine pixels tall and the recogniser returns
     * nothing at all. The live feedback would then sit permanently on "point at the ingredient
     * list" while pointed straight at one — the most confusing possible failure, because every
     * visible part of the feature would look like it was working.
     */
    private static final Size ANALYSIS_RESOLUTION = new Size(1280, 960);

    private PreviewView previewView;
    private GuideOverlayView guideOverlay;
    private MaterialButton captureButton;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private LiveLabelReader labelReader;
    private CaptureFeedback feedback;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService analysisExecutor;
    private MaterialButton torchButton;

    private boolean capturing;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    explainPermissionRefusal();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_camera_capture);
        applyInsets();
        View cameraRoot = findViewById(R.id.camera_root);
        View cameraScroll = findViewById(R.id.camera_scroll);
        cameraScroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop || right - left != oldRight - oldLeft) {
                view.post(() -> fitPreviewToViewport(cameraRoot));
            }
        });

        previewView = findViewById(R.id.camera_preview);
        guideOverlay = findViewById(R.id.guide_overlay);
        captureButton = findViewById(R.id.capture_button);
        MaterialButton closeButton = findViewById(R.id.capture_close_button);
        MaterialButton autoButton = findViewById(R.id.capture_auto_button);
        torchButton = findViewById(R.id.capture_torch_button);
        torchButton.setCheckable(true);

        analysisExecutor = Executors.newSingleThreadExecutor();
        feedback = new CaptureFeedback(guideOverlay, findViewById(R.id.capture_status),
                (ImageView) findViewById(R.id.capture_status_icon),
                (MaterialTextView) findViewById(R.id.capture_status_text), captureButton,
                this::capturePhoto);
        labelReader = new LiveLabelReader(feedback, viewGeometry, analysisExecutor);
        bindAutoCaptureToggle(autoButton);

        captureButton.setOnClickListener(view -> capturePhoto());
        closeButton.setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    /**
     * Explains a refused CAMERA permission instead of closing on the spot.
     *
     * <p>Worth the dialog because of what this screen replaced. The old flow used
     * {@code ActivityResultContracts.TakePicture}, which needs no permission of our own at all, so
     * the in-app preview introduced a refusal path that had never existed. Finishing silently made
     * it indistinguishable from a crash: the user taps the camera button, the screen flashes, and
     * they are back where they started with nothing said. After a permanent denial that repeats on
     * every tap, with no way to discover the cause, because Android stops showing the system prompt
     * entirely and the launch returns denied immediately.
     *
     * <p>Two cases, told apart by {@code shouldShowRequestPermissionRationale}: a one-off denial can
     * simply be asked again, while a permanent one can only be undone in Settings, so that is where
     * the button goes. Either way the dismissal still returns {@code RESULT_CANCELED} and the caller
     * is unchanged — the user keeps the gallery picker, which needs no camera permission.
     */
    private void explainPermissionRefusal() {
        boolean canAskAgain =
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camera_permission_title)
                .setMessage(canAskAgain
                        ? R.string.camera_permission_message
                        : R.string.camera_permission_message_blocked)
                .setNegativeButton(R.string.cancel, (dialog, which) -> cancelAndFinish())
                // Dismissing by back gesture or outside tap must leave by the same door, or the
                // screen sits on a dead black preview with no way forward.
                .setOnCancelListener(dialog -> cancelAndFinish());
        if (canAskAgain) {
            builder.setPositiveButton(R.string.camera_permission_retry,
                    (dialog, which) -> permissionLauncher.launch(Manifest.permission.CAMERA));
        } else {
            builder.setPositiveButton(R.string.camera_permission_settings, (dialog, which) -> {
                openAppSettings();
                cancelAndFinish();
            });
        }
        builder.show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            // No settings activity to answer the intent. Nothing left to offer, and the dialog has
            // already said what is wrong, so leaving quietly beats crashing.
            Log.w("CameraCapture", "No app-details settings screen available", exception);
        }
    }

    private void cancelAndFinish() {
        setResult(RESULT_CANCELED);
        finish();
    }

    /**
     * Wires the top-bar auto-capture toggle to the stored preference.
     *
     * <p>Checkable is set here rather than in the layout because the attribute that makes a
     * {@link MaterialButton} checkable is not the platform one of the same name, so the XML form is
     * easy to get silently wrong.
     */
    private void bindAutoCaptureToggle(@NonNull MaterialButton autoButton) {
        autoButton.setCheckable(true);
        boolean enabled = CameraPreferences.autoCapture(this);
        autoButton.setChecked(enabled);
        describeAutoCapture(autoButton, enabled);
        feedback.setAutoCaptureEnabled(enabled);
        autoButton.addOnCheckedChangeListener((button, checked) -> {
            CameraPreferences.saveAutoCapture(this, checked);
            feedback.setAutoCaptureEnabled(checked);
            describeAutoCapture(autoButton, checked);
        });
    }

    private void describeAutoCapture(@NonNull MaterialButton autoButton, boolean enabled) {
        describeCameraToggle(autoButton, R.string.camera_auto_capture,
                enabled ? R.string.camera_auto_capture_on : R.string.camera_auto_capture_off, enabled);
    }

    @Override
    protected void onDestroy() {
        // Ordered so nothing can deliver into a dead screen: stop new frames, then release the
        // recogniser (which fails any task still pending), then the thread, then the camera.
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        if (labelReader != null) {
            labelReader.close();
        }
        if (feedback != null) {
            feedback.freeze();
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
        // No unbindAll() here on purpose: bindToLifecycle already releases these use cases when this
        // activity is destroyed, and calling it explicitly is actively harmful — a replacement
        // instance can bind before the outgoing one is destroyed, and the late unbindAll() would tear
        // down the new screen's camera instead of the old one's.
        super.onDestroy();
    }

    /** Insets as root padding, exactly as MainActivity does it. */
    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.camera_root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            if (isFinishing() || isDestroyed()) {
                // The future resolves asynchronously, so by now the screen may be gone; binding into
                // a dead lifecycle leaves the camera held open.
                return;
            }
            try {
                cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().build();
                imageAnalysis = buildImageAnalysis();
                imageAnalysis.setAnalyzer(analysisExecutor, labelReader);
                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, imageAnalysis);
                bindTorch(camera);
                feedback.start();
            } catch (Exception exception) {
                // Logged, not just swallowed. A silent finish() here is indistinguishable from the
                // screen refusing to open at all, which is exactly how the display-rotation bug
                // above went unnoticed: the camera simply stopped working after the first use.
                Log.e("CameraCapture", "Unable to start the camera", exception);
                setResult(RESULT_CANCELED);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Wires the torch toggle to the camera that was just bound.
     *
     * <p>The torch is never restored from anywhere — a fresh binding starts dark and the toggle
     * starts unchecked. That is deliberate rather than an omission: a light that switches itself on
     * in a shop or a restaurant is startling, and only the user knows whether they need it.
     *
     * <p>The button follows {@code getTorchState} rather than its own click, so it stays truthful if
     * the torch is turned off by anything other than a tap — the camera being released and rebound
     * across a stop/start, or the system shutting it down when the device overheats.
     *
     * <p>That alone is not enough, though, which is why the click path also waits on its own future.
     * A rejected {@code enableTorch} never changes the torch state, so the observer never fires, and
     * a {@link MaterialButton} flips its own {@code checked} on the tap before the listener even
     * runs — leaving the button filled, and announcing a light that is off. The failure is not
     * theoretical: the control is rejected while the camera is closing, and on an overheating device
     * it is rejected for as long as the thermal throttle lasts.
     */
    private void bindTorch(@NonNull Camera camera) {
        if (!camera.getCameraInfo().hasFlashUnit()) {
            // No flash on this camera, so the control would do nothing. Gone rather than disabled:
            // a permanently dead button is worse than no button.
            torchButton.setVisibility(View.GONE);
            return;
        }
        torchButton.setVisibility(View.VISIBLE);
        camera.getCameraControl().enableTorch(false);
        camera.getCameraInfo().getTorchState().observe(this,
                state -> describeTorch(state != null && state == TorchState.ON));
        torchButton.setOnClickListener(view -> requestTorch(camera, torchButton.isChecked()));
    }

    /** Puts the torch button in the state {@code on}, both visibly and for a screen reader. */
    private void describeTorch(boolean on) {
        describeCameraToggle(torchButton, R.string.camera_flashlight,
                on ? R.string.camera_flashlight_on : R.string.camera_flashlight_off, on);
    }

    /** Asks for the torch, putting the button back where it was if the camera refuses. */
    private void requestTorch(@NonNull Camera camera, boolean on) {
        ListenableFuture<Void> request = camera.getCameraControl().enableTorch(on);
        request.addListener(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            try {
                request.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                revertTorch(camera, exception);
            } catch (ExecutionException | CancellationException exception) {
                revertTorch(camera, exception);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void revertTorch(@NonNull Camera camera, @NonNull Throwable cause) {
        Log.w("CameraCapture", "The camera refused the torch request", cause);
        // Re-read the real state rather than inverting the tap: between the request and its failure
        // the torch may have been changed by something else entirely.
        Integer state = camera.getCameraInfo().getTorchState().getValue();
        describeTorch(state != null && state == TorchState.ON);
        Snackbar.make(findViewById(R.id.camera_root), R.string.camera_flashlight_unavailable,
                Snackbar.LENGTH_SHORT).show();
    }

    /** Stable control names let native checkable semantics report the state once. */
    static void describeCameraToggle(@NonNull MaterialButton button, int label, int tooltip,
            boolean checked) {
        button.setCheckable(true);
        button.setChecked(checked);
        CharSequence name = button.getResources().getText(label);
        if (!name.equals(button.getContentDescription())) button.setContentDescription(name);
        button.setTooltipText(button.getResources().getText(tooltip));
    }

    /** Keep a useful preview while letting large text and the shutter scroll in a short window. */
    static void fitPreviewToViewport(@NonNull View root) {
        View scroll = root.findViewById(R.id.camera_scroll);
        View body = root.findViewById(R.id.camera_content_body);
        View column = root.findViewById(R.id.camera_column);
        View card = root.findViewById(R.id.camera_card);
        if (scroll.getHeight() == 0 || column.getHeight() == 0) return;
        int minimum = Math.round(160 * root.getResources().getDisplayMetrics().density);
        int fixed = column.getHeight() - card.getHeight();
        int height = Math.max(minimum, scroll.getHeight() - body.getPaddingTop()
                - body.getPaddingBottom() - fixed);
        ViewGroup.LayoutParams params = card.getLayoutParams();
        if (params.height != height) {
            params.height = height;
            card.setLayoutParams(params);
        }
    }

    private ImageAnalysis buildImageAnalysis() {
        return new ImageAnalysis.Builder()
                // Only the newest frame matters for live feedback; queueing stale ones would make
                // the hints lag behind the camera.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // The activity is locked to portrait, so this never changes — set explicitly so the
                // rotation LiveLabelReader un-does is meaningful rather than incidental.
                .setTargetRotation(displayRotation())
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(ANALYSIS_RESOLUTION,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                .build();
    }

    /**
     * The display's rotation, without assuming the preview is attached to a window yet.
     *
     * <p>{@code PreviewView.getDisplay()} returns null until the view is attached, and this runs from
     * the camera-provider callback, which is not ordered against layout. The first launch happens to
     * be safe — initialising the provider takes long enough for the hierarchy to attach — but on
     * every launch after that the provider is already a warm singleton, the callback arrives almost
     * immediately, and the view is not attached. Dereferencing the null display there threw straight
     * into the catch below, so the capture screen opened and closed again in about 60 ms and the
     * camera appeared dead for the rest of the session.
     */
    private int displayRotation() {
        Display display = previewView.getDisplay();
        if (display == null) {
            display = getDisplay();
        }
        // The activity is locked to portrait, so this fallback is the real answer, not a guess.
        return display != null ? display.getRotation() : Surface.ROTATION_0;
    }

    /** Main-thread view state the analyser needs; see {@link LiveLabelReader.ViewGeometry}. */
    private final LiveLabelReader.ViewGeometry viewGeometry = new LiveLabelReader.ViewGeometry() {

        @Nullable
        @Override
        public RectF judgeWindow() {
            return guideOverlay.judgeWindow();
        }

        @Nullable
        @Override
        public Matrix recognisedImageToView(@NonNull ImageProxy source) {
            // getSensorToViewTransform is @UiThread and null until the preview is streaming, which
            // is why this indirection exists rather than the analyser reading the view itself.
            return LiveLabelReader.recognisedImageToView(
                    previewView.getSensorToViewTransform(), source);
        }
    };

    private void capturePhoto() {
        if (imageCapture == null || capturing) {
            // Guarded because takePicture is asynchronous and the button stayed live during it, so a
            // double tap used to enqueue a second capture that landed after finish().
            return;
        }
        String outputPath = getIntent().getStringExtra(EXTRA_OUTPUT_FILE_PATH);
        if (outputPath == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        capturing = true;
        findViewById(R.id.capture_auto_button).setEnabled(false);
        torchButton.setEnabled(false);
        // Freeze the overlay on the framing that was actually photographed, and stop spending CPU on
        // analysis during the shot.
        feedback.beginCapture();
        imageAnalysis.clearAnalyzer();

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(new File(outputPath)).build();
        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                        setResult(RESULT_OK, new Intent());
                        finish();
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                });
    }
}
