package com.example.allergycheck;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.allergycheck.ocr.LiveLabelReader;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
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

    private boolean capturing;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    setResult(RESULT_CANCELED);
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_camera_capture);
        applyInsets();

        previewView = findViewById(R.id.camera_preview);
        guideOverlay = findViewById(R.id.guide_overlay);
        captureButton = findViewById(R.id.capture_button);
        MaterialButton closeButton = findViewById(R.id.capture_close_button);
        MaterialButton autoButton = findViewById(R.id.capture_auto_button);

        analysisExecutor = Executors.newSingleThreadExecutor();
        feedback = new CaptureFeedback(guideOverlay, findViewById(R.id.capture_status),
                (ImageView) findViewById(R.id.capture_status_icon),
                (MaterialTextView) findViewById(R.id.capture_status_text), captureButton,
                this::capturePhoto);
        labelReader = new LiveLabelReader(feedback, viewGeometry);
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
            // The fill change is the sighted affordance; this is its spoken equivalent.
            autoButton.announceForAccessibility(autoButton.getContentDescription());
        });
    }

    private void describeAutoCapture(@NonNull MaterialButton autoButton, boolean enabled) {
        autoButton.setContentDescription(getString(
                enabled ? R.string.camera_auto_capture_on : R.string.camera_auto_capture_off));
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
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture, imageAnalysis);
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
        captureButton.setEnabled(false);
        // Freeze the overlay on the framing that was actually photographed, and stop spending CPU on
        // analysis during the shot.
        feedback.freeze();
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
