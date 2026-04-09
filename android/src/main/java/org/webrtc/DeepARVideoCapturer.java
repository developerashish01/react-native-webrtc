package org.webrtc;

import android.app.Activity;
import android.content.Context;
import android.media.Image;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.deepar.ar.ARErrorType;
import ai.deepar.ar.AREventListener;
import ai.deepar.ar.ARTouchInfo;
import ai.deepar.ar.DeepAR;
import ai.deepar.ar.DeepARImageFormat;
import ai.deepar.ar.DeepARPixelFormat;

/**
 * Captures camera frames with CameraX, runs them through DeepAR offscreen renderer, and forwards
 * the processed frames into WebRTC as VideoFrames.
 */
public class DeepARVideoCapturer implements VideoCapturer, AREventListener {
    private static final String TAG = DeepARVideoCapturer.class.getSimpleName();
    private static final int NUMBER_OF_INPUT_BUFFERS = 2;

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

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ProcessCameraProvider cameraProvider;
    private ByteBuffer[] inputBuffers;
    private int currentInputBuffer;
    private volatile int inputRotation;

    @Nullable
    private CapturerEventsListener capturerEventsListener;

    public DeepARVideoCapturer(Activity activity, DeepARCaptureConfig captureConfig) {
        this.activity = activity;
        this.captureConfig = captureConfig;
        this.targetWidth = captureConfig.getWidth();
        this.targetHeight = captureConfig.getHeight();
        this.targetFps = captureConfig.getFrameRate();
    }

    public void setCapturerEventsListener(@Nullable CapturerEventsListener capturerEventsListener) {
        this.capturerEventsListener = capturerEventsListener;
    }

    @Override
    public synchronized void initialize(
            SurfaceTextureHelper surfaceTextureHelper,
            Context applicationContext,
            CapturerObserver capturerObserver) {
        this.applicationContext = applicationContext;
        this.capturerObserver = capturerObserver;
    }

    @Override
    public synchronized void startCapture(int width, int height, int framerate) {
        if (capturing) {
            return;
        }

        if (applicationContext == null || capturerObserver == null) {
            throw new IllegalStateException("DeepARVideoCapturer must be initialized before startCapture.");
        }

        this.targetWidth = width;
        this.targetHeight = height;
        this.targetFps = framerate;
        this.capturing = true;

        frameThread = new HandlerThread("DeepARFrameThread");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
        cameraExecutor = Executors.newSingleThreadExecutor();

        deepAR = new DeepAR(applicationContext);
        deepAR.setLicenseKey(captureConfig.getLicenseKey());
        deepAR.initialize(applicationContext, this);
        deepAR.setOffscreenRendering(targetWidth, targetHeight, DeepARPixelFormat.RGBA);

        inputBuffers = new ByteBuffer[NUMBER_OF_INPUT_BUFFERS];
        for (int i = 0; i < NUMBER_OF_INPUT_BUFFERS; i++) {
            inputBuffers[i] = ByteBuffer.allocateDirect(targetWidth * targetHeight * 4);
            inputBuffers[i].order(ByteOrder.nativeOrder());
        }
        currentInputBuffer = 0;

        bindCamera();
        capturerObserver.onCapturerStarted(true);
    }

    @Override
    public synchronized void stopCapture() {
        if (!capturing) {
            return;
        }

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
        }

        inputBuffers = null;
        if (capturerObserver != null) {
            capturerObserver.onCapturerStopped();
        }
    }

    @Override
    public synchronized void changeCaptureFormat(int width, int height, int framerate) {
        this.targetWidth = width;
        this.targetHeight = height;
        this.targetFps = framerate;

        if (deepAR != null) {
            deepAR.setOffscreenRendering(targetWidth, targetHeight, DeepARPixelFormat.RGBA);
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
        stopCapture();
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    private void bindCamera() {
        if (!(activity instanceof LifecycleOwner)) {
            Log.e(TAG, "Current activity is not a LifecycleOwner; cannot bind CameraX.");
            if (capturerEventsListener != null) {
                capturerEventsListener.onCapturerEnded();
            }
            return;
        }

        cameraProviderFuture = ProcessCameraProvider.getInstance(applicationContext);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = cameraProviderFuture.get();
                cameraProvider = provider;
                bindImageAnalysis(provider);
            } catch (Exception e) {
                Log.e(TAG, "Failed to bind camera provider", e);
                if (capturerEventsListener != null) {
                    capturerEventsListener.onCapturerEnded();
                }
            }
        }, ContextCompat.getMainExecutor(applicationContext));
    }

    private void bindImageAnalysis(@NonNull ProcessCameraProvider provider) {
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(targetWidth, targetHeight))
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, this::onCameraImage);

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(captureConfig.getLensFacing())
                .build();

        provider.unbindAll();
        provider.bindToLifecycle((LifecycleOwner) activity, cameraSelector, imageAnalysis);
    }

    private void onCameraImage(@NonNull ImageProxy imageProxy) {
        if (!capturing || deepAR == null || inputBuffers == null) {
            imageProxy.close();
            return;
        }

        ByteBuffer source = imageProxy.getPlanes()[0].getBuffer();
        source.rewind();

        ByteBuffer deepARInput = inputBuffers[currentInputBuffer];
        deepARInput.clear();

        if (source.remaining() > deepARInput.remaining()) {
            Log.w(TAG, "Dropping frame due to oversized camera input buffer.");
            imageProxy.close();
            return;
        }

        deepARInput.put(source);
        deepARInput.position(0);

        inputRotation = imageProxy.getImageInfo().getRotationDegrees();

        deepAR.receiveFrame(
                deepARInput,
                imageProxy.getWidth(),
                imageProxy.getHeight(),
                inputRotation,
                captureConfig.getLensFacing() == CameraSelector.LENS_FACING_FRONT,
                DeepARImageFormat.RGBA_8888,
                imageProxy.getPlanes()[0].getPixelStride());

        currentInputBuffer = (currentInputBuffer + 1) % NUMBER_OF_INPUT_BUFFERS;
        imageProxy.close();
    }

    private synchronized void rebindCamera() {
        if (cameraProvider == null || !capturing) {
            return;
        }
        bindImageAnalysis(cameraProvider);
    }

    private synchronized void unbindCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
    }

    private synchronized void releaseDeepAR() {
        if (deepAR != null) {
            deepAR.setAREventListener(null);
            deepAR.release();
            deepAR = null;
        }
    }

    @Override
    public void initialized() {
        Log.d(TAG, "DeepAR initialized.");
    }

    @Override
    public void shutdownFinished() {
        Log.d(TAG, "DeepAR shutdown finished.");
    }

    @Override
    public void faceVisibilityChanged(boolean visible) {
    }

    @Override
    public void imageVisibilityChanged(String imageName, boolean visible) {
    }

    @Override
    public void frameAvailable(Image image) {
        Handler handler = frameHandler;
        if (!capturing || handler == null) {
            image.close();
            return;
        }

        handler.post(() -> {
            VideoFrame frame = null;
            try {
                if (!capturing || capturerObserver == null) {
                    return;
                }

                VideoFrame.I420Buffer buffer = DeepARFrameConverter.toI420(image);
                long timestampNs = SystemClock.elapsedRealtimeNanos();
                frame = new VideoFrame(buffer, inputRotation, timestampNs);
                capturerObserver.onFrameCaptured(frame);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to convert/send DeepAR frame", e);
            } finally {
                if (frame != null) {
                    frame.release();
                }
                image.close();
            }
        });
    }

    @Override
    public void screenshotTaken(android.graphics.Bitmap bitmap) {
    }

    @Override
    public void videoRecordingStarted() {
    }

    @Override
    public void videoRecordingFinished() {
    }

    @Override
    public void videoRecordingFailed() {
    }

    @Override
    public void videoRecordingPrepared() {
    }

    @Override
    public void effectSwitched(String effect) {
    }

    @Override
    public void error(ARErrorType errorType, String errorText) {
        Log.e(TAG, "DeepAR error " + errorType + ": " + errorText);
        if (capturerEventsListener != null) {
            capturerEventsListener.onCapturerEnded();
        }
    }

    @Override
    public void startedVideoRecording() {
    }

    @Override
    public void finishedVideoRecording() {
    }

    @Override
    public void audioRecordingPrepared() {
    }

    @Override
    public void audioRecordingStarted() {
    }

    @Override
    public void audioRecordingFinished() {
    }

    @Override
    public void audioRecordingFailed() {
    }

    @Override
    public void cameraPermissionAsked() {
    }

    @Override
    public void cameraPermissionGranted() {
    }

    @Override
    public void cameraPermissionDenied() {
    }

    @Override
    public void resetStatus() {
    }

    @Override
    public void openGLContextPrepared() {
    }

    @Override
    public void touchOccurred(ARTouchInfo touchInfo) {
    }
}
