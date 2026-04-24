package org.webrtc;

import android.app.Activity;
import android.content.Context;
import android.media.Image;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.oney.WebRTCModule.deepar.DeepARCaptureConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        if (deepAR == null || effectPath == null || effectPath.isEmpty()) {
            Log.e(ASHISH, "switchEffect: DeepAR not initialized or effectPath empty");
            return;
        }
        long startTime = System.currentTimeMillis();
        boolean result = runOnFrameThreadBlocking("switch effect", () -> switchEffectInternal(effectPath));
        long endTime = System.currentTimeMillis();
        Log.d(ASHISH, "switchEffect finished for effectPath=" + effectPath + ", duration=" + (endTime - startTime) + "ms, result=" + result);
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
            deepAR.setOffscreenRendering(targetWidth, targetHeight);
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
        Log.d(
            ZOOM_DEBUG_TAG,
            "changeCaptureFormat target=" + width + "x" + height + " fps=" + framerate + " targetAspect=" + safeAspect(width, height));
        updateFrameRateThrottle(this.targetFps);

        if (deepAR != null) {
            runOnFrameThreadBlocking("set offscreen rendering", () -> {
                Log.d(ASHISH, "Setting offscreen rendering in changeCaptureFormat");
                deepAR.setOffscreenRendering(targetWidth, targetHeight);
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

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Force a natural camera aspect ratio to avoid center-crop zoom in DeepAR preview.
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build();

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

    private void onCameraImage(long sessionId, @NonNull ImageProxy imageProxy) {
        if (sessionId != captureSessionId) {
            imageProxy.close();
            return;
        }

        if (!capturing || deepAR == null || inputBuffers == null) {
            imageProxy.close();
            return;
        }

        long frameTimestampNs = SystemClock.elapsedRealtimeNanos();
        long previousFrameTimestampNs = lastFrameSubmittedNs;
        if (previousFrameTimestampNs != 0L
                && frameTimestampNs - previousFrameTimestampNs < minFrameIntervalNs) {
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

        ByteBuffer bufferForDeepAR;
        int outputRowStride;

        if (pixelStride == 4 && rowStride == width * 4 && source.limit() >= (width * height * 4)) {
            // Zero-copy for tightly packed RGBA frames.
            bufferForDeepAR = source;
            outputRowStride = rowStride;
        } else {
            int bufferIndex = currentInputBuffer;
            ByteBuffer deepARInput = inputBuffers[bufferIndex];
            int requiredCapacity = width * height * 4;
            if (deepARInput.capacity() < requiredCapacity) {
                inputBuffers[bufferIndex] = ByteBuffer.allocateDirect(requiredCapacity);
                inputBuffers[bufferIndex].order(ByteOrder.nativeOrder());
                deepARInput = inputBuffers[bufferIndex];
            }

            packRgba8888(source, width, height, rowStride, pixelStride, deepARInput);
            bufferForDeepAR = deepARInput;
            outputRowStride = width * 4;
            currentInputBuffer = (currentInputBuffer + 1) % NUMBER_OF_INPUT_BUFFERS;
        }

        if (cameraFrameCount % 120 == 0) {
            Log.d(ASHISH, "onCameraImage strides width=" + width + " height=" + height + " rowStride=" + rowStride + " pixelStride=" + pixelStride);
        }

        final int rotation = imageProxy.getImageInfo().getRotationDegrees();
        inputRotation = rotation;
        lastCameraInputWidth = width;
        lastCameraInputHeight = height;
        lastCameraInputRotation = rotation;
        // Jitsi integration expects non-mirrored DeepAR output.
        final boolean mirror = false;


        Runnable doReceiveFrame = () -> {
            try {
                if (!capturing || deepAR == null) {
                    imageProxy.close();
                    return;
                }
                deepAR.receiveFrame(
                        bufferForDeepAR,
                        width,
                        height,
                        rotation,
                        mirror,
                        DeepARImageFormat.RGBA_8888,
                        outputRowStride);

                cameraFrameCount++;
                if (cameraFrameCount % 120 == 1) {
                    Log.d(TAG, "onCameraImage #" + cameraFrameCount + " size=" + width + "x" + height + " rotation=" + rotation);
                }
                if (cameraFrameCount % 60 == 1) {
                    logZoomEstimate(
                            "camera->deepar",
                            width,
                            height,
                            targetWidth,
                            targetHeight,
                            rotation,
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
        Log.d(ASHISH, "switchEffectInternal: About to apply effectPath=" + effectPath);
        long start = System.currentTimeMillis();
        deepAR.switchEffect("effect", effectPath);
        long end = System.currentTimeMillis();
        Log.d(ASHISH, "switchEffectInternal: Applied effectPath=" + effectPath + ", duration=" + (end - start) + "ms");
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
            if (deepARFrameCount % 100 == 1) {
                Log.d(TAG, "frameAvailable #" + deepARFrameCount + " from DeepAR: " + image.getWidth() + "x" + image.getHeight() + " format=" + image.getFormat());
                Log.d(ASHISH, "frameAvailable #" + deepARFrameCount + " from DeepAR: " + image.getWidth() + "x" + image.getHeight() + " format=" + image.getFormat());
            }
            if (deepARFrameCount % 60 == 1) {
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
            // DeepAR output already contains the requested orientation. Passing inputRotation here
            // causes double-rotation in RTCView on some devices.
            frame = new VideoFrame(buffer, 0, timestampNs);
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
