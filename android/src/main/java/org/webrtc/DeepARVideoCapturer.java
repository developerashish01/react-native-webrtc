package org.webrtc;

import android.app.Activity;
import android.content.Context;
import android.media.Image;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.oney.WebRTCModule.deepar.DeepARCaptureConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ai.deepar.ar.ARErrorType;
import ai.deepar.ar.AREventListener;
import ai.deepar.ar.DeepAR;
import ai.deepar.ar.DeepARImageFormat;

/**
 * Captures camera frames with CameraX, runs them through DeepAR offscreen renderer, and forwards
 * the processed frames into WebRTC as VideoFrames.
 */
public class DeepARVideoCapturer implements VideoCapturer, AREventListener {
    private static final String TAG = DeepARVideoCapturer.class.getSimpleName();
    private static final String ASHISH = "ASHISH";
    private static final String ZOOM_DEBUG_TAG = "DeepARZoom";
    private static final int NUMBER_OF_INPUT_BUFFERS = 2;
    private static final int MAX_CAMERA_INPUT_PIXELS = 1280 * 720;
    private static final long FRAME_THREAD_SYNC_TIMEOUT_MS = 5000;
    private static final long MAIN_THREAD_SYNC_TIMEOUT_MS = 5000;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public interface CapturerEventsListener {
        void onCapturerEnded();
    }

    private final Activity activity;
    private final DeepARCaptureConfig captureConfig;

    private Context applicationContext;
    private CapturerObserver capturerObserver;
    private DeepAR deepAR;

    private volatile boolean capturing;
    private int targetWidth;
    private int targetHeight;
    private int targetFps;

    private ExecutorService cameraExecutor;
    private HandlerThread frameThread;
    private Handler frameHandler;
    private Executor deepARExecutor;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private ByteBuffer[] inputBuffers;
    private int currentInputBuffer;
    private volatile int inputRotation;
    private volatile long minFrameIntervalNs;
    private volatile long lastFrameSubmittedNs;
    private volatile long captureSessionId;
    private volatile int lastCameraInputWidth;
    private volatile int lastCameraInputHeight;
    private volatile int lastCameraInputRotation;
    private volatile long deepARThreadId;
    private volatile int appliedRenderWidth;
    private volatile int appliedRenderHeight;
    private volatile int effectResetGeneration;
    private volatile long droppedFramesThrottle;
    private volatile long droppedFramesInactive;

    @Nullable
    private CapturerEventsListener capturerEventsListener;

    public DeepARVideoCapturer(Activity activity, DeepARCaptureConfig captureConfig) {
        Log.d(ASHISH, "DeepARVideoCapturer constructor called");
        this.activity = activity;
        this.captureConfig = captureConfig;
        this.targetWidth = captureConfig.getWidth();
        this.targetHeight = captureConfig.getHeight();
        this.targetFps = captureConfig.getFrameRate();
        updateFrameRateThrottle(this.targetFps);
        Log.d(ASHISH, "DeepARVideoCapturer initialized with width=" + targetWidth + ", height=" + targetHeight + ", fps=" + targetFps);
    }

    /**
     * Switches the DeepAR effect at runtime. Can be called from the main project via the controller chain.
     * @param effectPath The path to the new DeepAR effect.
     */
    public void switchEffect(final String effectPath) {
        Log.d(ASHISH, "switchEffect called with effectPath=" + effectPath);
        if (!capturing || deepAR == null || effectPath == null) {
            Log.e(ASHISH, "switchEffect: capturer not running, DeepAR not initialized, or effectPath null");
            return;
        }

        final String resolvedEffectPath = DeepARCaptureConfig.normalizeEffectPath(effectPath);
        final String pathToApply = resolvedEffectPath == null ? "" : resolvedEffectPath;
        final long expectedSessionId = captureSessionId;
        runOnFrameThread("switch effect", () -> {
            if (!capturing || deepAR == null || captureSessionId != expectedSessionId) {
                Log.d(ASHISH, "switchEffect skipped for stale/inactive session=" + expectedSessionId);
                return;
            }
            long startTime = System.currentTimeMillis();
            switchEffectInternal(pathToApply);
            long endTime = System.currentTimeMillis();
            Log.d(ASHISH, "switchEffect finished for effectPath=" + pathToApply + ", duration=" + (endTime - startTime) + "ms");
        });
    }

    private void updateFrameRateThrottle(int fps) {
        int effectiveFps = Math.max(1, fps);
        this.minFrameIntervalNs = NANOS_PER_SECOND / effectiveFps;
        this.lastFrameSubmittedNs = 0L;
    }

    private static double safeAspect(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 0.0;
        }
        return (double) width / (double) height;
    }

    private int getDesiredRenderWidth() {
        return targetWidth;
    }

    private int getDesiredRenderHeight() {
        return targetHeight;
    }

    private void applyOffscreenRenderingOnFrameThread(int width, int height, String reason) {
        if (deepAR == null || width <= 0 || height <= 0) {
            return;
        }

        if (appliedRenderWidth == width && appliedRenderHeight == height) {
            return;
        }

        Log.d(
                ZOOM_DEBUG_TAG,
                "setOffscreenRendering reason=" + reason
                        + " render=" + width + "x" + height
                        + " renderAspect=" + safeAspect(width, height)
                        + " cameraInput=" + lastCameraInputWidth + "x" + lastCameraInputHeight);
        deepAR.setOffscreenRendering(width, height);
        appliedRenderWidth = width;
        appliedRenderHeight = height;
    }

    private void scheduleOffscreenRenderingReset(String reason) {
        Handler handler = frameHandler;
        if (handler == null) {
            Log.w(ASHISH, "scheduleOffscreenRenderingReset skipped: frameHandler is null reason=" + reason);
            return;
        }

        final int resetGeneration = ++effectResetGeneration;
        final long expectedSessionId = captureSessionId;
        Log.d(ASHISH, "scheduleOffscreenRenderingReset queued reason=" + reason + " session=" + expectedSessionId + " generation=" + resetGeneration + " delayMs=50");
        handler.postDelayed(() -> {
            if (!capturing || deepAR == null || captureSessionId != expectedSessionId || resetGeneration != effectResetGeneration) {
                Log.d(ASHISH, "Skipping delayed offscreen reset reason=" + reason + " session=" + expectedSessionId + " generation=" + resetGeneration);
                return;
            }

            applyOffscreenRenderingOnFrameThread(getDesiredRenderWidth(), getDesiredRenderHeight(), reason);
        }, 50L);
    }

    private static void logZoomEstimate(
            String stage,
            int inputWidth,
            int inputHeight,
            int renderWidth,
            int renderHeight,
            int rotation,
            int rowStride,
            int outputRowStride,
            boolean mirror,
            int frameNumber) {
        if (inputWidth <= 0 || inputHeight <= 0 || renderWidth <= 0 || renderHeight <= 0) {
            return;
        }

        double sx = (double) renderWidth / (double) inputWidth;
        double sy = (double) renderHeight / (double) inputHeight;
        double scale = Math.max(sx, sy);
        double usedInputWidth = renderWidth / scale;
        double usedInputHeight = renderHeight / scale;
        double cropWidthPercent = Math.max(0.0, (1.0 - (usedInputWidth / inputWidth)) * 100.0);
        double cropHeightPercent = Math.max(0.0, (1.0 - (usedInputHeight / inputHeight)) * 100.0);

        Log.d(
                ZOOM_DEBUG_TAG,
                stage
                        + " frame#=" + frameNumber
                        + " in=" + inputWidth + "x" + inputHeight
                        + " render=" + renderWidth + "x" + renderHeight
                        + " inAspect=" + safeAspect(inputWidth, inputHeight)
                        + " renderAspect=" + safeAspect(renderWidth, renderHeight)
                        + " estCropW%=" + cropWidthPercent
                        + " estCropH%=" + cropHeightPercent
                        + " rotation=" + rotation
                        + " mirror=" + mirror
                        + " inRowStride=" + rowStride
                        + " deepARRowStride=" + outputRowStride);
    }

    public void setCapturerEventsListener(@Nullable CapturerEventsListener capturerEventsListener) {
        Log.d(ASHISH, "setCapturerEventsListener called");
        this.capturerEventsListener = capturerEventsListener;
    }

    @Override
    public synchronized void initialize(
            SurfaceTextureHelper surfaceTextureHelper,
            Context applicationContext,
            CapturerObserver capturerObserver) {
        Log.d(TAG, "initialize() called");
        Log.d(ASHISH, "initialize() called");
        this.applicationContext = applicationContext;
        this.capturerObserver = capturerObserver;
    }

    @Override
    public synchronized void startCapture(int width, int height, int framerate) {

        Log.d(ASHISH, "startCapture called with width=" + width + ", height=" + height + ", framerate=" + framerate);
        if (capturing) {
            Log.d(ASHISH, "Already capturing, returning");
            return;
        }

        if (applicationContext == null || capturerObserver == null) {
            Log.e(ASHISH, "Application context or capturerObserver is null in startCapture");
            throw new IllegalStateException("DeepARVideoCapturer must be initialized before startCapture.");
        }

        // Prevent crash: check if activity is finishing or not running
        if (activity == null) {
            Log.e(ASHISH, "DeepARVideoCapturer: Activity is null, cannot initialize DeepAR");
            return;
        }
        if (activity.isFinishing()) {
            Log.e(ASHISH, "DeepARVideoCapturer: Activity is finishing, cannot initialize DeepAR");
            return;
        }
        // Optionally, check for isDestroyed (API 17+)
        try {
            java.lang.reflect.Method isDestroyed = Activity.class.getMethod("isDestroyed");
            boolean destroyed = (boolean) isDestroyed.invoke(activity);
            if (destroyed) {
                Log.e(ASHISH, "DeepARVideoCapturer: Activity is destroyed, cannot initialize DeepAR");
                return;
            }
        } catch (Exception ignore) {}

        this.targetWidth = width;
        this.targetHeight = height;
        this.targetFps = Math.max(1, framerate);
        updateFrameRateThrottle(this.targetFps);
        final long sessionId = captureSessionId + 1;
        captureSessionId = sessionId;
        this.capturing = true;
        this.lastCameraInputWidth = 0;
        this.lastCameraInputHeight = 0;
        this.lastCameraInputRotation = 0;

        frameThread = new HandlerThread("DeepARFrameThread");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
        deepARThreadId = frameThread.getLooper().getThread().getId();
        deepARExecutor = command -> {
            Handler handler = frameHandler;
            if (handler != null) {
                handler.post(command);
            }
        };
        cameraExecutor = Executors.newSingleThreadExecutor();

        Log.d(TAG, "startCapture() w=" + width + " h=" + height + " fps=" + framerate);
        Log.d(
            ZOOM_DEBUG_TAG,
            "startCapture target=" + width + "x" + height + " fps=" + framerate + " targetAspect=" + safeAspect(width, height));
        Log.d(TAG, "License key (first 8): " + (captureConfig.getLicenseKey() != null ? captureConfig.getLicenseKey().substring(0, Math.min(8, captureConfig.getLicenseKey().length())) + "..." : "NULL"));
        Log.d(TAG, "Effect path: " + captureConfig.getEffectPath());
        Log.d(TAG, "Lens facing: " + (captureConfig.getLensFacing() == CameraSelector.LENS_FACING_FRONT ? "front" : "back"));

        boolean deepARInitOk = runOnFrameThreadBlocking("initialize DeepAR", () -> {
            Log.d(TAG, "ASHISH: About to create DeepAR instance");
            Log.d(ASHISH, "About to create DeepAR instance");
            deepAR = new DeepAR(applicationContext);
            Log.d(TAG, "ASHISH: DeepAR instance created");
            Log.d(ASHISH, "DeepAR instance created");
            Log.d(TAG, "ASHISH: About to set DeepAR license key");
            Log.d(ASHISH, "About to set DeepAR license key");
            deepAR.setLicenseKey(captureConfig.getLicenseKey());
            Log.d(TAG, "ASHISH: DeepAR license key set");
            Log.d(ASHISH, "DeepAR license key set");
            Log.d(TAG, "ASHISH: About to initialize DeepAR");
            Log.d(ASHISH, "About to initialize DeepAR");
            deepAR.initialize(applicationContext, this);
            Log.d(TAG, "ASHISH: DeepAR initialized");
            Log.d(ASHISH, "DeepAR initialized");
            Log.d(TAG, "ASHISH: About to set offscreen rendering");
            Log.d(ASHISH, "About to set offscreen rendering");
            applyOffscreenRenderingOnFrameThread(targetWidth, targetHeight, "initialization");
            Log.d(TAG, "ASHISH: Offscreen rendering set");
            Log.d(ASHISH, "Offscreen rendering set");
        });
        if (!deepARInitOk) {
            Log.e(ASHISH, "DeepAR initialization failed on frame thread");
            capturing = false;
            if (cameraExecutor != null) {
                cameraExecutor.shutdownNow();
                cameraExecutor = null;
            }
            if (frameThread != null) {
                frameThread.quitSafely();
                frameThread = null;
                frameHandler = null;
            }
            if (capturerObserver != null) {
                capturerObserver.onCapturerStarted(false);
            }
            return;
        }

        inputBuffers = new ByteBuffer[NUMBER_OF_INPUT_BUFFERS];
        for (int i = 0; i < NUMBER_OF_INPUT_BUFFERS; i++) {
            inputBuffers[i] = ByteBuffer.allocateDirect(targetWidth * targetHeight * 4);
            inputBuffers[i].order(ByteOrder.nativeOrder());
        }
        currentInputBuffer = 0;

        bindCamera(sessionId);
        capturerObserver.onCapturerStarted(true);
    }

    @Override
    public synchronized void stopCapture() {
        Log.d(ASHISH, "stopCapture called");
        if (!capturing) {
            Log.d(ASHISH, "Not capturing, returning from stopCapture");
            return;
        }

        captureSessionId++;
        capturing = false;
        effectResetGeneration++;
        if (frameHandler != null) {
            frameHandler.removeCallbacksAndMessages(null);
        }
        unbindCamera();
        releaseDeepAR();

        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
            cameraExecutor = null;
        }

        if (frameThread != null) {
            frameThread.quitSafely();
            frameThread = null;
            frameHandler = null;
            deepARExecutor = null;
            deepARThreadId = 0L;
        }

        inputBuffers = null;
        if (capturerObserver != null) {
            capturerObserver.onCapturerStopped();
        }
    }

    @Override
    public synchronized void changeCaptureFormat(int width, int height, int framerate) {
        Log.d(ASHISH, "changeCaptureFormat called with width=" + width + ", height=" + height + ", framerate=" + framerate);
        this.targetWidth = width;
        this.targetHeight = height;
        this.targetFps = Math.max(1, framerate);
        this.appliedRenderWidth = 0;
        this.appliedRenderHeight = 0;
        Log.d(
            ZOOM_DEBUG_TAG,
            "changeCaptureFormat target=" + width + "x" + height + " fps=" + framerate + " targetAspect=" + safeAspect(width, height));
        updateFrameRateThrottle(this.targetFps);

        if (deepAR != null) {
            runOnFrameThreadBlocking("set offscreen rendering", () -> {
                Log.d(ASHISH, "Setting offscreen rendering in changeCaptureFormat");
                applyOffscreenRenderingOnFrameThread(targetWidth, targetHeight, "changeCaptureFormat");
                Log.d(ASHISH, "Offscreen rendering set in changeCaptureFormat");
            });
        }

        if (capturing) {
            inputBuffers = new ByteBuffer[NUMBER_OF_INPUT_BUFFERS];
            for (int i = 0; i < NUMBER_OF_INPUT_BUFFERS; i++) {
                inputBuffers[i] = ByteBuffer.allocateDirect(targetWidth * targetHeight * 4);
                inputBuffers[i].order(ByteOrder.nativeOrder());
            }
            rebindCamera();
        }
    }

    @Override
    public synchronized void dispose() {
        Log.d(ASHISH, "dispose called");
        stopCapture();
    }

    @Override
    public boolean isScreencast() {
        Log.d(ASHISH, "isScreencast called");
        return false;
    }

    private void bindCamera(long sessionId) {
        Log.d(ASHISH, "bindCamera called");
        Log.d(ASHISH, "Camera invocation: bindCamera entry");
        if (!(activity instanceof LifecycleOwner)) {
            Log.e(ASHISH, "Activity is not a LifecycleOwner in bindCamera");
            Log.e(TAG, "Current activity is not a LifecycleOwner; cannot bind CameraX.");
            if (capturerEventsListener != null) {
                capturerEventsListener.onCapturerEnded();
            }
            Log.d(ASHISH, "Camera invocation: bindCamera failed - not a LifecycleOwner");
            return;
        }

        cameraProviderFuture = ProcessCameraProvider.getInstance(applicationContext);
        Log.d(ASHISH, "Camera invocation: requested ProcessCameraProvider instance");
        cameraProviderFuture.addListener(() -> {
            if (!capturing || captureSessionId != sessionId) {
                Log.d(ASHISH, "Ignoring stale camera provider callback for session=" + sessionId + " activeSession=" + captureSessionId);
                return;
            }
            Log.d(ASHISH, "cameraProviderFuture listener triggered");
            try {
                ProcessCameraProvider provider = cameraProviderFuture.get();
                cameraProvider = provider;
                Log.d(ASHISH, "Camera invocation: ProcessCameraProvider acquired");
                bindImageAnalysis(provider, sessionId);
                Log.d(ASHISH, "Camera provider bound and image analysis set");
            } catch (Exception e) {
                Log.e(ASHISH, "Failed to bind camera provider: " + e.getMessage());
                Log.e(TAG, "Failed to bind camera provider", e);
                if (capturerEventsListener != null) {
                    capturerEventsListener.onCapturerEnded();
                }
                Log.d(ASHISH, "Camera invocation: Exception in cameraProviderFuture listener");
            }
        }, ContextCompat.getMainExecutor(applicationContext));
    }

    private void bindImageAnalysis(@NonNull ProcessCameraProvider provider, long sessionId) {
        Log.d(ASHISH, "bindImageAnalysis called");
        Log.d(ASHISH, "Camera invocation: bindImageAnalysis entry");
        if (!capturing || captureSessionId != sessionId) {
            Log.d(ASHISH, "Skipping bindImageAnalysis for stale session=" + sessionId + " activeSession=" + captureSessionId);
            return;
        }


        if (frameHandler == null) {
            Log.e(ASHISH, "Cannot bind analyzer because frameHandler is null");
            if (capturerEventsListener != null) {
                capturerEventsListener.onCapturerEnded();
            }
            return;
        }

        double targetAspect = safeAspect(targetWidth, targetHeight);

        Size targetResolution = new Size(targetWidth, targetHeight);
        long targetPixels = (long) targetWidth * (long) targetHeight;
        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(new ResolutionStrategy(
                    targetResolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .setResolutionFilter((supportedSizes, rotationDegrees) -> {
                List<Size> preferredSizes = new ArrayList<>();
                List<Size> nearHigherSizes = new ArrayList<>();
                List<Size> lowerSizes = new ArrayList<>();
                for (Size size : supportedSizes) {
                    if (size.getWidth() == targetWidth && size.getHeight() == targetHeight) {
                        preferredSizes.add(size);
                    }
                }
                for (Size size : supportedSizes) {
                    long pixels = (long) size.getWidth() * (long) size.getHeight();
                    if (preferredSizes.contains(size)) {
                        continue;
                    }
                    if (pixels > targetPixels && pixels <= MAX_CAMERA_INPUT_PIXELS) {
                        nearHigherSizes.add(size);
                    } else if (pixels <= targetPixels) {
                        lowerSizes.add(size);
                    }
                }
                nearHigherSizes.sort(Comparator.comparingLong(size ->
                        ((long) size.getWidth() * (long) size.getHeight()) - targetPixels));
                lowerSizes.sort((left, right) -> Long.compare(
                        (long) right.getWidth() * (long) right.getHeight(),
                        (long) left.getWidth() * (long) left.getHeight()));
                preferredSizes.addAll(nearHigherSizes);
                preferredSizes.addAll(lowerSizes);
                return preferredSizes.isEmpty() ? supportedSizes : preferredSizes;
            })
            .build();

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)
            .setBackgroundExecutor(cameraExecutor)
            .build();

        Log.d(
            ZOOM_DEBUG_TAG,
            "bindImageAnalysis target=" + targetWidth + "x" + targetHeight
                + " targetAspect=" + targetAspect
                + " requestedAnalysisResolution=" + targetWidth + "x" + targetHeight
                + " fps=" + targetFps);

        // Run analyzer directly on DeepAR executor so receiveFrame stays on the init thread.
        Executor analyzerExecutor = deepARExecutor;
        if (analyzerExecutor == null) {
            Log.e(ASHISH, "Cannot bind analyzer because deepARExecutor is null");
            if (capturerEventsListener != null) {
                capturerEventsListener.onCapturerEnded();
            }
            return;
        }
        imageAnalysis.setAnalyzer(analyzerExecutor, image -> onCameraImage(sessionId, image));
        Log.d(ASHISH, "Camera invocation: setAnalyzer on ImageAnalysis");

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(captureConfig.getLensFacing())
                .build();

        boolean bound = runOnMainThreadBlocking("bind image analysis", () -> {
            provider.unbindAll();
            Log.d(ASHISH, "Camera invocation: provider.unbindAll called");
            provider.bindToLifecycle((LifecycleOwner) activity, cameraSelector, imageAnalysis);
            Log.d(ASHISH, "Camera invocation: provider.bindToLifecycle called");
            Log.d(TAG, "CameraX bound. lensFacing=" + captureConfig.getLensFacing() + " target=" + targetWidth + "x" + targetHeight);
            Log.d(ASHISH, "CameraX bound in bindImageAnalysis");
            if (imageAnalysis.getResolutionInfo() != null) {
                Size selectedResolution = imageAnalysis.getResolutionInfo().getResolution();
                Log.d(ASHISH, "CameraX selected analysis resolution=" + selectedResolution.getWidth() + "x" + selectedResolution.getHeight());
            }
        });
        if (!bound) {
            Log.e(ASHISH, "Failed to bind CameraX on main thread");
            if (capturerEventsListener != null) {
                capturerEventsListener.onCapturerEnded();
            }
        }
    }

    private int cameraFrameCount = 0;

    private static void packRgba8888(
            ByteBuffer src,
            int width,
            int height,
            int rowStride,
            int pixelStride,
            ByteBuffer dst) {
        dst.clear();
        final int dstRowBytes = width * 4;
        final int srcLimit = src.limit();

        if (pixelStride == 4 && rowStride == dstRowBytes && srcLimit >= (dstRowBytes * height)) {
            // Fast path: source is already tightly packed RGBA.
            ByteBuffer dup = src.duplicate();
            dup.position(0);
            dup.limit(dstRowBytes * height);
            dst.put(dup);
            dst.position(0);
            return;
        }

        if (pixelStride == 4) {
            // Common padded-row case: copy one RGBA row at a time instead of pixel-by-pixel.
            ByteBuffer dstDup = dst.duplicate();
            for (int y = 0; y < height; y++) {
                int srcRowOffset = y * rowStride;
                int dstRowOffset = y * dstRowBytes;
                int availableBytes = Math.max(0, srcLimit - srcRowOffset);
                int copyBytes = Math.min(dstRowBytes, availableBytes);

                dstDup.position(dstRowOffset);
                if (copyBytes > 0) {
                    ByteBuffer rowView = src.duplicate();
                    rowView.position(srcRowOffset);
                    rowView.limit(srcRowOffset + copyBytes);
                    dstDup.put(rowView);
                }

                if (copyBytes < dstRowBytes) {
                    for (int i = copyBytes; i < dstRowBytes; i++) {
                        dstDup.put((byte) 0);
                    }
                }
            }
            dst.position(0);
            return;
        }

        // General path: copy each RGBA pixel accounting for CameraX row/pixel stride.
        for (int y = 0; y < height; y++) {
            int srcRowOffset = y * rowStride;
            int dstRowOffset = y * dstRowBytes;
            for (int x = 0; x < width; x++) {
                int srcPixelOffset = srcRowOffset + x * pixelStride;
                int dstPixelOffset = dstRowOffset + x * 4;
                if (srcPixelOffset + 3 < srcLimit) {
                    dst.put(dstPixelOffset, src.get(srcPixelOffset));
                    dst.put(dstPixelOffset + 1, src.get(srcPixelOffset + 1));
                    dst.put(dstPixelOffset + 2, src.get(srcPixelOffset + 2));
                    dst.put(dstPixelOffset + 3, src.get(srcPixelOffset + 3));
                } else {
                    dst.put(dstPixelOffset, (byte) 0);
                    dst.put(dstPixelOffset + 1, (byte) 0);
                    dst.put(dstPixelOffset + 2, (byte) 0);
                    dst.put(dstPixelOffset + 3, (byte) 0);
                }
            }
        }
        dst.position(0);
    }

    private static int normalizeRotation(int rotation) {
        int normalized = rotation % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    private static int clampToRange(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void fillRgbaRect(
            IntBuffer dst,
            int dstWidth,
            int dstHeight,
            int left,
            int top,
            int right,
            int bottom,
            int color) {
        int safeLeft = clampToRange(left, 0, dstWidth);
        int safeRight = clampToRange(right, 0, dstWidth);
        int safeTop = clampToRange(top, 0, dstHeight);
        int safeBottom = clampToRange(bottom, 0, dstHeight);
        if (safeLeft >= safeRight || safeTop >= safeBottom) {
            return;
        }

        for (int y = safeTop; y < safeBottom; y++) {
            int rowOffset = y * dstWidth;
            for (int x = safeLeft; x < safeRight; x++) {
                dst.put(rowOffset + x, color);
            }
        }
    }

    private static boolean renderRgbaAspectFitFast(
            ByteBuffer src,
            int srcWidth,
            int srcHeight,
            int rowStride,
            int pixelStride,
            int rotation,
            ByteBuffer dst,
            int dstWidth,
            int dstHeight,
            int contentWidth,
            int contentHeight,
            int offsetX,
            int offsetY,
            int orientedWidth,
            int orientedHeight,
            double scale) {
        if (pixelStride != 4 || rowStride % 4 != 0) {
            return false;
        }

        ByteBuffer srcBytes = src.duplicate().order(ByteOrder.nativeOrder());
        ByteBuffer dstBytes = dst.duplicate().order(ByteOrder.nativeOrder());
        IntBuffer srcInts = srcBytes.asIntBuffer();
        IntBuffer dstInts = dstBytes.asIntBuffer();
        int srcRowStrideInts = rowStride / 4;
        int blackRgba = 0xFF000000;
        int normalizedRotation = normalizeRotation(rotation);

        fillRgbaRect(dstInts, dstWidth, dstHeight, 0, 0, dstWidth, offsetY, blackRgba);
        fillRgbaRect(dstInts, dstWidth, dstHeight, 0, offsetY + contentHeight, dstWidth, dstHeight, blackRgba);
        fillRgbaRect(dstInts, dstWidth, dstHeight, 0, offsetY, offsetX, offsetY + contentHeight, blackRgba);
        fillRgbaRect(dstInts, dstWidth, dstHeight, offsetX + contentWidth, offsetY, dstWidth, offsetY + contentHeight, blackRgba);

        for (int y = 0; y < contentHeight; y++) {
            int dstY = offsetY + y;
            int orientedY = clampToRange((int) (y / scale), 0, orientedHeight - 1);
            int dstRowOffset = dstY * dstWidth + offsetX;
            for (int x = 0; x < contentWidth; x++) {
                int orientedX = clampToRange((int) (x / scale), 0, orientedWidth - 1);
                int srcX;
                int srcY;

                switch (normalizedRotation) {
                    case 90:
                        srcX = orientedY;
                        srcY = srcHeight - 1 - orientedX;
                        break;
                    case 180:
                        srcX = srcWidth - 1 - orientedX;
                        srcY = srcHeight - 1 - orientedY;
                        break;
                    case 270:
                        srcX = srcWidth - 1 - orientedY;
                        srcY = orientedX;
                        break;
                    case 0:
                    default:
                        srcX = orientedX;
                        srcY = orientedY;
                        break;
                }

                srcX = clampToRange(srcX, 0, srcWidth - 1);
                srcY = clampToRange(srcY, 0, srcHeight - 1);
                dstInts.put(dstRowOffset + x, srcInts.get(srcY * srcRowStrideInts + srcX));
            }
        }

        dst.position(0);
        return true;
    }

    private static void copyRgbaPixel(
            ByteBuffer src,
            int srcWidth,
            int srcHeight,
            int rowStride,
            int pixelStride,
            int rotation,
            int orientedX,
            int orientedY,
            ByteBuffer dst,
            int dstOffset) {
        int srcX;
        int srcY;

        switch (normalizeRotation(rotation)) {
            case 90:
                srcX = orientedY;
                srcY = srcHeight - 1 - orientedX;
                break;
            case 180:
                srcX = srcWidth - 1 - orientedX;
                srcY = srcHeight - 1 - orientedY;
                break;
            case 270:
                srcX = srcWidth - 1 - orientedY;
                srcY = orientedX;
                break;
            case 0:
            default:
                srcX = orientedX;
                srcY = orientedY;
                break;
        }

        srcX = clampToRange(srcX, 0, srcWidth - 1);
        srcY = clampToRange(srcY, 0, srcHeight - 1);

        int srcOffset = srcY * rowStride + srcX * pixelStride;
        if (srcOffset + 3 < src.limit()) {
            dst.put(dstOffset, src.get(srcOffset));
            dst.put(dstOffset + 1, src.get(srcOffset + 1));
            dst.put(dstOffset + 2, src.get(srcOffset + 2));
            dst.put(dstOffset + 3, src.get(srcOffset + 3));
        } else {
            dst.put(dstOffset, (byte) 0);
            dst.put(dstOffset + 1, (byte) 0);
            dst.put(dstOffset + 2, (byte) 0);
            dst.put(dstOffset + 3, (byte) 255);
        }
    }

    private static void renderRgbaAspectFit(
            ByteBuffer src,
            int srcWidth,
            int srcHeight,
            int rowStride,
            int pixelStride,
            int rotation,
            ByteBuffer dst,
            int dstWidth,
            int dstHeight) {
        int normalizedRotation = normalizeRotation(rotation);
        int orientedWidth = (normalizedRotation == 90 || normalizedRotation == 270) ? srcHeight : srcWidth;
        int orientedHeight = (normalizedRotation == 90 || normalizedRotation == 270) ? srcWidth : srcHeight;
        if (orientedWidth <= 0 || orientedHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            dst.position(0);
            return;
        }

        double scale = Math.min((double) dstWidth / orientedWidth, (double) dstHeight / orientedHeight);
        int contentWidth = Math.max(1, (int) Math.round(orientedWidth * scale));
        int contentHeight = Math.max(1, (int) Math.round(orientedHeight * scale));
        int offsetX = (dstWidth - contentWidth) / 2;
        int offsetY = (dstHeight - contentHeight) / 2;

        if (renderRgbaAspectFitFast(
                src,
                srcWidth,
                srcHeight,
                rowStride,
                pixelStride,
                rotation,
                dst,
                dstWidth,
                dstHeight,
                contentWidth,
                contentHeight,
                offsetX,
                offsetY,
                orientedWidth,
                orientedHeight,
                scale)) {
            return;
        }

        dst.clear();
        for (int i = 0; i < dstWidth * dstHeight; i++) {
            int offset = i * 4;
            dst.put(offset, (byte) 0);
            dst.put(offset + 1, (byte) 0);
            dst.put(offset + 2, (byte) 0);
            dst.put(offset + 3, (byte) 255);
        }

        for (int y = 0; y < contentHeight; y++) {
            int dstY = offsetY + y;
            int orientedY = clampToRange((int) (y / scale), 0, orientedHeight - 1);
            for (int x = 0; x < contentWidth; x++) {
                int dstX = offsetX + x;
                int orientedX = clampToRange((int) (x / scale), 0, orientedWidth - 1);
                int dstOffset = (dstY * dstWidth + dstX) * 4;
                copyRgbaPixel(
                        src,
                        srcWidth,
                        srcHeight,
                        rowStride,
                        pixelStride,
                        rotation,
                        orientedX,
                        orientedY,
                        dst,
                        dstOffset);
            }
        }

        dst.position(0);
    }

    private void onCameraImage(long sessionId, @NonNull ImageProxy imageProxy) {
        if (sessionId != captureSessionId) {
            imageProxy.close();
            return;
        }

        if (!capturing || deepAR == null || inputBuffers == null) {
            droppedFramesInactive++;
            if (droppedFramesInactive % 120 == 1) {
                Log.d(ASHISH, "onCameraImage dropped(inactive) count=" + droppedFramesInactive + " capturing=" + capturing + " deepARNull=" + (deepAR == null) + " buffersNull=" + (inputBuffers == null));
            }
            imageProxy.close();
            return;
        }

        long frameTimestampNs = SystemClock.elapsedRealtimeNanos();
        long previousFrameTimestampNs = lastFrameSubmittedNs;
        if (previousFrameTimestampNs != 0L
                && frameTimestampNs - previousFrameTimestampNs < minFrameIntervalNs) {
            droppedFramesThrottle++;
            if (droppedFramesThrottle % 120 == 1) {
                Log.d(ASHISH, "onCameraImage dropped(throttle) count=" + droppedFramesThrottle + " minIntervalNs=" + minFrameIntervalNs + " deltaNs=" + (frameTimestampNs - previousFrameTimestampNs));
            }
            imageProxy.close();
            return;
        }
        lastFrameSubmittedNs = frameTimestampNs;

        ByteBuffer source = imageProxy.getPlanes()[0].getBuffer();
        source.rewind();

        final int width = imageProxy.getWidth();
        final int height = imageProxy.getHeight();
        final int pixelStride = imageProxy.getPlanes()[0].getPixelStride();
        final int rowStride = imageProxy.getPlanes()[0].getRowStride();

        if (cameraFrameCount % 120 == 0) {
            Log.d(ASHISH, "onCameraImage strides width=" + width + " height=" + height + " rowStride=" + rowStride + " pixelStride=" + pixelStride);
            Log.d(
                ZOOM_DEBUG_TAG,
                "camera-input-vs-target"
                    + " input=" + width + "x" + height
                    + " target=" + targetWidth + "x" + targetHeight
                    + " inputAspect=" + safeAspect(width, height)
                    + " targetAspect=" + safeAspect(targetWidth, targetHeight)
                    + " captureRotation=" + imageProxy.getImageInfo().getRotationDegrees());
        }

        final int rotation = imageProxy.getImageInfo().getRotationDegrees();
        inputRotation = rotation;
        lastCameraInputWidth = width;
        lastCameraInputHeight = height;
        lastCameraInputRotation = rotation;
        // Jitsi integration expects non-mirrored DeepAR output.
        final boolean mirror = false;
        final int deepARRotation = 0;
        final int deepARInputWidth = targetWidth;
        final int deepARInputHeight = targetHeight;
        final int outputRowStride = deepARInputWidth * 4;

        int bufferIndex = currentInputBuffer;
        ByteBuffer deepARInputBuffer = inputBuffers[bufferIndex];
        int requiredCapacity = deepARInputWidth * deepARInputHeight * 4;
        if (deepARInputBuffer.capacity() < requiredCapacity) {
            inputBuffers[bufferIndex] = ByteBuffer.allocateDirect(requiredCapacity);
            inputBuffers[bufferIndex].order(ByteOrder.nativeOrder());
            deepARInputBuffer = inputBuffers[bufferIndex];
        }
        if (rotation == 0 && width == deepARInputWidth && height == deepARInputHeight) {
            packRgba8888(source, width, height, rowStride, pixelStride, deepARInputBuffer);
        } else {
            renderRgbaAspectFit(
                    source,
                    width,
                    height,
                    rowStride,
                    pixelStride,
                    rotation,
                    deepARInputBuffer,
                    deepARInputWidth,
                    deepARInputHeight);
        }
        currentInputBuffer = (currentInputBuffer + 1) % NUMBER_OF_INPUT_BUFFERS;
        final ByteBuffer bufferForDeepAR = deepARInputBuffer;

        Runnable doReceiveFrame = () -> {
            try {
                if (!capturing || deepAR == null) {
                    imageProxy.close();
                    return;
                }
                deepAR.receiveFrame(
                        bufferForDeepAR,
                        deepARInputWidth,
                        deepARInputHeight,
                        deepARRotation,
                        mirror,
                        DeepARImageFormat.RGBA_8888,
                        outputRowStride);

                cameraFrameCount++;
                if (cameraFrameCount % 120 == 1) {
                    Log.d(TAG, "onCameraImage #" + cameraFrameCount + " size=" + width + "x" + height + " rotation=" + rotation);
                }
                if (cameraFrameCount % 120 == 1) {
                    logZoomEstimate(
                            "camera->deepar",
                            width,
                            height,
                            deepARInputWidth,
                            deepARInputHeight,
                            deepARRotation,
                            rowStride,
                            outputRowStride,
                            mirror,
                            cameraFrameCount);
                }
            } catch (RuntimeException e) {
                Log.e(ASHISH, "deepAR.receiveFrame failed: " + e.getMessage());
                Log.e(TAG, "deepAR.receiveFrame failed", e);
            } finally {
                imageProxy.close();
            }
        };

        long currentThreadId = Thread.currentThread().getId();
        if (deepARThreadId != 0L && currentThreadId != deepARThreadId && frameHandler != null) {
            Log.w(ASHISH, "onCameraImage rerouting frame to DeepAR thread: current=" + currentThreadId + " expected=" + deepARThreadId + " name=" + Thread.currentThread().getName());
            frameHandler.post(doReceiveFrame);
        } else {
            doReceiveFrame.run();
        }
    }

    private synchronized void rebindCamera() {
        Log.d(ASHISH, "rebindCamera called");
        Log.d(ASHISH, "Camera invocation: rebindCamera entry");
        if (cameraProvider == null || !capturing) {
            Log.d(ASHISH, "cameraProvider null or not capturing in rebindCamera");
            Log.d(ASHISH, "Camera invocation: rebindCamera early exit");
            return;
        }
        bindImageAnalysis(cameraProvider, captureSessionId);
        Log.d(ASHISH, "Camera invocation: rebindCamera completed");
    }

    private synchronized void unbindCamera() {
        Log.d(ASHISH, "unbindCamera called");
        Log.d(ASHISH, "Camera invocation: unbindCamera entry");
        ProcessCameraProvider provider = cameraProvider;
        if (provider != null) {
            boolean unbound = runOnMainThreadBlocking("unbind camera", provider::unbindAll);
            if (!unbound) {
                Log.e(ASHISH, "Failed to unbind CameraX on main thread");
            }
            cameraProvider = null;
            Log.d(ASHISH, "cameraProvider unbound in unbindCamera");
            Log.d(ASHISH, "Camera invocation: cameraProvider unbound in unbindCamera");
        }
    }

    private synchronized void releaseDeepAR() {
        Log.d(ASHISH, "releaseDeepAR called");
        if (deepAR == null) {
            return;
        }
        if (!runOnFrameThreadBlocking("release DeepAR", () -> {
            if (deepAR != null) {
                Log.d(TAG, "ASHISH: About to release DeepAR");
                deepAR.setAREventListener(null);
                deepAR.release();
                deepAR = null;
                Log.d(TAG, "ASHISH: DeepAR released");
                Log.d(ASHISH, "DeepAR released in releaseDeepAR");
            }
        })) {
            Log.w(ASHISH, "Falling back to direct DeepAR release due to frame-thread sync failure");
            try {
                deepAR.setAREventListener(null);
                deepAR.release();
            } catch (RuntimeException e) {
                Log.e(TAG, "Direct DeepAR release failed", e);
            }
            deepAR = null;
        }
    }

    private boolean runOnFrameThreadBlocking(String action, Runnable task) {
        Handler handler = frameHandler;
        Log.d(ASHISH, "runOnFrameThreadBlocking(" + action + ") called. handler=" + handler + " looper=" + (handler != null ? handler.getLooper() : "null") + " currentLooper=" + Looper.myLooper());
        if (handler == null) {
            Log.e(ASHISH, "runOnFrameThreadBlocking(" + action + ") failed: frameHandler is null");
            return false;
        }

        if (Looper.myLooper() == handler.getLooper()) {
            deepARThreadId = Thread.currentThread().getId();
            Log.d(ASHISH, "runOnFrameThreadBlocking(" + action + ") running directly on frame thread");
            try {
                task.run();
                Log.d(ASHISH, "runOnFrameThreadBlocking(" + action + ") task.run() finished");
                return true;
            } catch (RuntimeException e) {
                Log.e(TAG, "runOnFrameThreadBlocking(" + action + ") failed", e);
                return false;
            }
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RuntimeException> errorRef = new AtomicReference<>();

        boolean posted = handler.post(() -> {
            Log.d(ASHISH, "runOnFrameThreadBlocking(" + action + ") Runnable started on thread: " + Thread.currentThread().getName());
            try {
                deepARThreadId = Thread.currentThread().getId();
                task.run();
                Log.d(ASHISH, "runOnFrameThreadBlocking(" + action + ") Runnable finished");
            } catch (RuntimeException e) {
                errorRef.set(e);
            } finally {
                latch.countDown();
            }
        });

        Log.d(ASHISH, "runOnFrameThreadBlocking(" + action + ") posted=" + posted);

        if (!posted) {
            Log.e(ASHISH, "runOnFrameThreadBlocking(" + action + ") failed: unable to post task");
            return false;
        }

        try {
            boolean completed = latch.await(FRAME_THREAD_SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Log.d(ASHISH, "runOnFrameThreadBlocking(" + action + ") latch completed=" + completed);
            if (!completed) {
                Log.e(ASHISH, "runOnFrameThreadBlocking(" + action + ") timed out after " + FRAME_THREAD_SYNC_TIMEOUT_MS + "ms");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(ASHISH, "runOnFrameThreadBlocking(" + action + ") interrupted", e);
            return false;
        }

        RuntimeException error = errorRef.get();
        if (error != null) {
            Log.e(TAG, "runOnFrameThreadBlocking(" + action + ") failed", error);
            return false;
        }

        return true;
    }

    private boolean runOnFrameThread(String action, Runnable task) {
        Handler handler = frameHandler;
        if (handler == null) {
            Log.e(ASHISH, "runOnFrameThread(" + action + ") failed: frameHandler is null");
            return false;
        }

        if (Looper.myLooper() == handler.getLooper()) {
            deepARThreadId = Thread.currentThread().getId();
            try {
                task.run();
                return true;
            } catch (RuntimeException e) {
                Log.e(TAG, "runOnFrameThread(" + action + ") failed", e);
                return false;
            }
        }

        boolean posted = handler.post(() -> {
            try {
                deepARThreadId = Thread.currentThread().getId();
                task.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "runOnFrameThread(" + action + ") failed", e);
            }
        });

        if (!posted) {
            Log.e(ASHISH, "runOnFrameThread(" + action + ") failed: unable to post task");
        }

        return posted;
    }

    private boolean runOnMainThreadBlocking(String action, Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                task.run();
                return true;
            } catch (RuntimeException e) {
                Log.e(TAG, "runOnMainThreadBlocking(" + action + ") failed", e);
                return false;
            }
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RuntimeException> errorRef = new AtomicReference<>();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        boolean posted = mainHandler.post(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                errorRef.set(e);
            } finally {
                latch.countDown();
            }
        });

        if (!posted) {
            Log.e(ASHISH, "runOnMainThreadBlocking(" + action + ") failed: unable to post task");
            return false;
        }

        try {
            boolean completed = latch.await(MAIN_THREAD_SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                Log.e(ASHISH, "runOnMainThreadBlocking(" + action + ") timed out after " + MAIN_THREAD_SYNC_TIMEOUT_MS + "ms");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(ASHISH, "runOnMainThreadBlocking(" + action + ") interrupted", e);
            return false;
        }

        RuntimeException error = errorRef.get();
        if (error != null) {
            Log.e(TAG, "runOnMainThreadBlocking(" + action + ") failed", error);
            return false;
        }

        return true;
    }

    @Override
    public void initialized() {
        Log.d(ASHISH, "DeepAR initialized callback received");
        Log.d(TAG, "DeepAR initialized callback received.");
        Log.d(
            ZOOM_DEBUG_TAG,
            "deepar-initialized-state"
                + " target=" + targetWidth + "x" + targetHeight
                + " appliedRender=" + appliedRenderWidth + "x" + appliedRenderHeight
                + " captureSessionId=" + captureSessionId
                + " capturing=" + capturing);
        String effectPath = captureConfig.getEffectPath();
        if (effectPath != null && !effectPath.isEmpty()) {
            if (!assetPathExists(effectPath)) {
                Log.e(ASHISH, "Requested DeepAR effect asset is missing: " + effectPath);
                Log.w(ASHISH, "Continuing with no DeepAR effect.");
                return;
            }
            Log.d(ASHISH, "Switching effect to: " + effectPath);
            Log.d(TAG, "Switching effect to: " + effectPath);
            final String resolvedEffectPath = effectPath;
            boolean switched = runOnFrameThreadBlocking("switch effect", () -> switchEffectInternal(resolvedEffectPath));
            if (!switched) {
                Log.e(ASHISH, "Failed to switch DeepAR effect on frame thread: " + effectPath);
            }
        } else {
            Log.w(ASHISH, "No effectPath provided — DeepAR will run as passthrough (no AR effect).");
            Log.w(TAG, "No effectPath provided — DeepAR will run as passthrough (no AR effect).");
        }
    }

    private void switchEffectInternal(String effectPath) {
        if (deepAR == null) {
            Log.e(ASHISH, "Cannot switch effect because DeepAR instance is null");
            return;
        }
        Log.d(
            ZOOM_DEBUG_TAG,
            "switch-effect-state-before"
                + " effectPath=" + effectPath
                + " target=" + targetWidth + "x" + targetHeight
                + " appliedRender=" + appliedRenderWidth + "x" + appliedRenderHeight
                + " lastInput=" + lastCameraInputWidth + "x" + lastCameraInputHeight
                + " inputRotation=" + lastCameraInputRotation
                + " capturing=" + capturing);
        Log.d(ASHISH, "switchEffectInternal: About to apply effectPath=" + effectPath);
        long start = System.currentTimeMillis();
        deepAR.switchEffect("effect", effectPath);
        long end = System.currentTimeMillis();
        Log.d(ASHISH, "switchEffectInternal: Applied effectPath=" + effectPath + ", duration=" + (end - start) + "ms");
        scheduleOffscreenRenderingReset("effect-switch");
    }

    private boolean assetPathExists(String path) {
        if (path == null || !path.startsWith("file:///android_asset/") || applicationContext == null) {
            return true;
        }

        String assetRelativePath = path.substring("file:///android_asset/".length());
        try {
            applicationContext.getAssets().open(assetRelativePath).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void shutdownFinished() {
        Log.d(ASHISH, "DeepAR shutdown finished");
        Log.d(TAG, "DeepAR shutdown finished.");
    }

    @Override
    public void faceVisibilityChanged(boolean visible) {
        Log.d(ASHISH, "faceVisibilityChanged: " + visible);
        Log.d(TAG, "faceVisibilityChanged: " + visible);
    }

    @Override
    public void imageVisibilityChanged(String imageName, boolean visible) {
        Log.d(ASHISH, "imageVisibilityChanged: " + imageName + " visible=" + visible);
        Log.d(TAG, "imageVisibilityChanged: " + imageName + " visible=" + visible);
    }

    private int deepARFrameCount = 0;

    @Override
    public void frameAvailable(Image image) {
        if (image == null) {
            Log.e(ASHISH, "frameAvailable: image is null");
            return;
        }
        // Defensive: try to access a property to check if image is already closed
        try {
            int w = image.getWidth();
        } catch (IllegalStateException e) {
            Log.e(ASHISH, "frameAvailable: image is already closed (early guard)");
            try { image.close(); } catch (Exception ignore) {}
            return;
        }
        if (!capturing || capturerObserver == null) {
            try { image.close(); } catch (Exception ignore) {}
            return;
        }
        VideoFrame frame = null;
        try {
            deepARFrameCount++;
            if (deepARFrameCount % 120 == 1) {
                Log.d(TAG, "frameAvailable #" + deepARFrameCount + " from DeepAR: " + image.getWidth() + "x" + image.getHeight() + " format=" + image.getFormat());
                Log.d(ASHISH, "frameAvailable #" + deepARFrameCount + " from DeepAR: " + image.getWidth() + "x" + image.getHeight() + " format=" + image.getFormat());
            }
            if (deepARFrameCount % 120 == 1) {
                logZoomEstimate(
                        "deepar->output",
                        lastCameraInputWidth,
                        lastCameraInputHeight,
                        image.getWidth(),
                        image.getHeight(),
                        lastCameraInputRotation,
                        /* rowStride */ -1,
                        /* outputRowStride */ -1,
                        captureConfig.getLensFacing() == CameraSelector.LENS_FACING_FRONT,
                        deepARFrameCount);
            }

            VideoFrame.I420Buffer buffer = DeepARFrameConverter.toI420(image);
            long timestampNs = SystemClock.elapsedRealtimeNanos();
            // DeepAR output is already rendered in output orientation.
            // Passing camera rotation metadata causes orientation regressions in RTCView.
            int rotationMetadata = 0;
            if (deepARFrameCount % 120 == 1) {
                Log.d(ASHISH, "deepar->webrtc rotationMetadata=" + rotationMetadata + " inputRotation=" + lastCameraInputRotation);
                Log.d(
                    ZOOM_DEBUG_TAG,
                    "deepar-output-vs-camera-input"
                        + " output=" + image.getWidth() + "x" + image.getHeight()
                        + " input=" + lastCameraInputWidth + "x" + lastCameraInputHeight
                        + " outputAspect=" + safeAspect(image.getWidth(), image.getHeight())
                        + " inputAspect=" + safeAspect(lastCameraInputWidth, lastCameraInputHeight)
                        + " target=" + targetWidth + "x" + targetHeight
                        + " targetAspect=" + safeAspect(targetWidth, targetHeight));
            }
            frame = new VideoFrame(buffer, rotationMetadata, timestampNs);
            capturerObserver.onFrameCaptured(frame);
        } catch (RuntimeException e) {
            Log.e(ASHISH, "Failed to convert/send DeepAR frame: " + e.getMessage());
            Log.e(TAG, "Failed to convert/send DeepAR frame", e);
        } finally {
            if (frame != null) {
                frame.release();
            }
            try { image.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public void screenshotTaken(android.graphics.Bitmap bitmap) {
        Log.d(ASHISH, "screenshotTaken called");
    }

    @Override
    public void videoRecordingStarted() {
        Log.d(ASHISH, "videoRecordingStarted called");
    }

    @Override
    public void videoRecordingFinished() {
        Log.d(ASHISH, "videoRecordingFinished called");
    }

    @Override
    public void videoRecordingFailed() {
        Log.d(ASHISH, "videoRecordingFailed called");
    }

    @Override
    public void videoRecordingPrepared() {
        Log.d(ASHISH, "videoRecordingPrepared called");
    }

    @Override
    public void effectSwitched(String effect) {
        Log.d(ASHISH, "effectSwitched: " + effect);
        Log.d(TAG, "effectSwitched: " + effect);
    }

    @Override
    public void error(ARErrorType errorType, String errorText) {
        Log.e(ASHISH, "DeepAR error " + errorType + ": " + errorText);
        Log.e(TAG, "DeepAR error " + errorType + ": " + errorText);
        if (capturerEventsListener != null) {
            capturerEventsListener.onCapturerEnded();
        }
    }
}
