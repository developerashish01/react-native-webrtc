package com.oney.WebRTCModule;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import org.webrtc.Camera1Capturer;
import org.webrtc.Camera1Helper;
import org.webrtc.Camera2Capturer;
import org.webrtc.Camera2Helper;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.Size;
import org.webrtc.VideoCapturer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CameraCaptureController extends AbstractVideoCaptureController {
    private static final String ASHISH = "ASHISH";
        // --- Logging for aspect/zoom/crop ---
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
                    ASHISH,
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
                            + " rowStride=" + rowStride
                            + " outputRowStride=" + outputRowStride);
        }

        private int normalCameraFrameCount = 0;
        private void logNormalCameraFrame(int inputWidth, int inputHeight, int renderWidth, int renderHeight, int rotation, int rowStride, int outputRowStride, boolean mirror) {
            normalCameraFrameCount++;
            if (normalCameraFrameCount % 60 == 1) {
                logZoomEstimate(
                    "camera->normal",
                    inputWidth,
                    inputHeight,
                    renderWidth,
                    renderHeight,
                    rotation,
                    rowStride,
                    outputRowStride,
                    mirror,
                    normalCameraFrameCount);
            }
        }
    /**
     * The {@link Log} tag with which {@code CameraCaptureController} is to log.
     */
    private static final String TAG = CameraCaptureController.class.getSimpleName();

    private boolean isFrontFacing;

    /**
     * Equivalent to the camera index as a String
     */
    @Nullable
    private String currentDeviceId;

    private final Context context;
    private final CameraEnumerator cameraEnumerator;
    private ReadableMap constraints;

    /**
     * The {@link CameraEventsHandler} used with
     * {@link CameraEnumerator#createCapturer}.
     */
    private final CameraEventsHandler cameraEventsHandler = new CameraEventsHandler() {
        @Override
        public void onCameraOpening(String cameraName) {
            super.onCameraOpening(cameraName);
            int cameraIndex = findCameraIndex(cameraName);
            updateActualSize(cameraIndex, cameraName, videoCapturer);
            CameraCaptureController.this.currentDeviceId = cameraIndex == -1 ? null : String.valueOf(cameraIndex);
        }
    };

    public CameraCaptureController(Context context, CameraEnumerator cameraEnumerator, ReadableMap constraints) {
        super(constraints.getInt("width"), constraints.getInt("height"), constraints.getInt("frameRate"));

        this.context = context;
        this.cameraEnumerator = cameraEnumerator;
        this.constraints = constraints;
        Log.d(ASHISH, "CameraCaptureController constructed with width=" + targetWidth + ", height=" + targetHeight + ", fps=" + targetFps);
        logZoomEstimate("normal-target:init", targetWidth, targetHeight, targetWidth, targetHeight, 0, -1, -1, false, 0);
    }

    @Nullable
    @Override
    public String getDeviceId() {
        return currentDeviceId;
    }

    private int findCameraIndex(String cameraName) {
        String[] deviceNames = cameraEnumerator.getDeviceNames();
        for (int i = 0; i < deviceNames.length; i++) {
            if (Objects.equals(deviceNames[i], cameraName)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public WritableMap getSettings() {
        WritableMap settings = super.getSettings();
        settings.putString("facingMode", isFrontFacing ? "user" : "environment");
        return settings;
    }

    @Override
    public void applyConstraints(ReadableMap constraints, @Nullable Consumer<Exception> onFinishedCallback) {
        ReadableMap oldConstraints = this.constraints;
        int oldTargetWidth = this.targetWidth;
        int oldTargetHeight = this.targetHeight;
        int oldTargetFps = this.targetFps;

        // Don't save constraints yet, since we may fail to find a fit.
        Runnable saveConstraints = () -> {
            this.constraints = constraints;
            this.targetWidth = constraints.getInt("width");
            this.targetHeight = constraints.getInt("height");
            this.targetFps = constraints.getInt("frameRate");
            Log.d(ASHISH, "applyConstraints: width=" + targetWidth + ", height=" + targetHeight + ", fps=" + targetFps);
            logZoomEstimate("ASHISHnormal-target:applyConstraints", targetWidth, targetHeight, targetWidth, targetHeight, 0, -1, -1, false, 0);
        };

        if (videoCapturer == null) {
            // No existing capturer, just let it initialize normally.
            saveConstraints.run();
            if (onFinishedCallback != null) {
                onFinishedCallback.accept(null);
            }
            return;
        }

        // Find target camera to switch to.
        String[] deviceNames = cameraEnumerator.getDeviceNames();
        final String deviceId = ReactBridgeUtil.getMapStrValue(constraints, "deviceId");
        final String facingMode = ReactBridgeUtil.getMapStrValue(constraints, "facingMode");
        int cameraIndex = -1;
        String cameraName = null;

        // If deviceId is specified, then it takes precedence over facingMode.
        if (deviceId != null) {
            try {
                cameraIndex = Integer.parseInt(deviceId);
                cameraName = deviceNames[cameraIndex];
            } catch (Exception e) {
                Log.d(TAG, "failed to find device with id: " + deviceId);
            }
        }

        // Otherwise, use facingMode (defaulting to front/user facing).
        if (cameraName == null) {
            cameraIndex = -1;
            final boolean isFrontFacing = facingMode == null || facingMode.equals("user");
            for (String name : deviceNames) {
                cameraIndex++;
                if (cameraEnumerator.isFrontFacing(name) == isFrontFacing) {
                    cameraName = name;
                    break;
                }
            }
        }

        if (cameraName == null) {
            if (onFinishedCallback != null) {
                onFinishedCallback.accept(new Exception("OverconstrainedError: could not find camera with deviceId: "
                        + deviceId + " or facingMode: " + facingMode));
            }
            return;
        }

        // For lambda reference
        final int finalCameraIndex = cameraIndex;
        final String finalCameraName = cameraName;
        boolean shouldSwitchCamera = false;
        try {
            int currentCameraIndex = Integer.parseInt(currentDeviceId);
            shouldSwitchCamera = cameraIndex != currentCameraIndex;
        } catch (Exception e) {
            shouldSwitchCamera = true;
            Log.d(TAG, "Forcing camera switch, couldn't parse current device id: " + currentDeviceId);
        }

        CameraVideoCapturer capturer = (CameraVideoCapturer) videoCapturer;
        Runnable changeFormatIfNeededAndFinish = () -> {
            saveConstraints.run();
            if (targetWidth != oldTargetWidth || targetHeight != oldTargetHeight || targetFps != oldTargetFps) {
                updateActualSize(finalCameraIndex, finalCameraName, videoCapturer);
                capturer.changeCaptureFormat(targetWidth, targetHeight, targetFps);
            }
            if (onFinishedCallback != null) {
                onFinishedCallback.accept(null);
            }
        };

        if (shouldSwitchCamera) {
            capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                @Override
                public void onCameraSwitchDone(boolean isFrontCamera) {
                    CameraCaptureController.this.isFrontFacing = isFrontCamera;
                    changeFormatIfNeededAndFinish.run();
                }

                @Override
                public void onCameraSwitchError(String s) {
                    Exception e = new Exception("Error switching camera: " + s);
                    Log.e(TAG, "OnCameraSwitchError", e);
                    if (onFinishedCallback != null) {
                        onFinishedCallback.accept(e);
                    }
                }
            }, cameraName);
        } else {
            // No camera switch needed, just change format if needed.
            changeFormatIfNeededAndFinish.run();
        }
    }

    @Override
    protected VideoCapturer createVideoCapturer() {
        String deviceId = ReactBridgeUtil.getMapStrValue(this.constraints, "deviceId");
        String facingMode = ReactBridgeUtil.getMapStrValue(this.constraints, "facingMode");

        Log.d(ASHISH, "createVideoCapturer called with deviceId=" + deviceId + ", facingMode=" + facingMode);
        CreateCapturerResult result = createVideoCapturer(deviceId, facingMode);
        if (result == null) {
            Log.e(ASHISH, "createVideoCapturer: No suitable camera found");
            return null;
        }

        Log.d(ASHISH, "createVideoCapturer: Camera selected: " + result.cameraName + " (index=" + result.cameraIndex + ")");
        updateActualSize(result.cameraIndex, result.cameraName, result.videoCapturer);

        return result.videoCapturer;
    }

    private void updateActualSize(int cameraIndex, String cameraName, VideoCapturer videoCapturer) {
        // Find actual capture format.
        Size actualSize = null;
        if (videoCapturer instanceof Camera1Capturer) {
            actualSize = Camera1Helper.findClosestCaptureFormat(cameraIndex, targetWidth, targetHeight);
        } else if (videoCapturer instanceof Camera2Capturer) {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            actualSize = Camera2Helper.findClosestCaptureFormat(cameraManager, cameraName, targetWidth, targetHeight);
        }

        if (actualSize != null) {
            actualWidth = actualSize.width;
            actualHeight = actualSize.height;
            logZoomEstimate(
                "normal-config:requested-vs-actual",
                actualWidth,
                actualHeight,
                targetWidth,
                targetHeight,
                0,
                -1,
                -1,
                false,
                0);
            Log.d(
                ASHISH,
                "normal camera requested=" + targetWidth + "x" + targetHeight
                    + " actual=" + actualWidth + "x" + actualHeight
                    + " reqAspect=" + safeAspect(targetWidth, targetHeight)
                    + " actualAspect=" + safeAspect(actualWidth, actualHeight));
        }
    }

    /**
     * Constructs a new {@code VideoCapturer} instance attempting to satisfy
     * specific constraints.
     *
     * @param deviceId the ID of the requested video device. If not
     * {@code null} and a {@code VideoCapturer} can be created for it, then
     * {@code facingMode} is ignored.
     * @param facingMode the facing of the requested video source such as
     * {@code user} and {@code environment}. If {@code null}, "user" is
     * presumed.
     * @return a pair containing the deviceId and {@code VideoCapturer} satisfying the {@code facingMode} or
     * {@code deviceId} constraint, or null.
     */
    @Nullable
    private CreateCapturerResult createVideoCapturer(String deviceId, String facingMode) {
        String[] deviceNames = cameraEnumerator.getDeviceNames();
        List<String> failedDevices = new ArrayList<>();

        String cameraName = null;
        int cameraIndex = -1;
        try {
            cameraIndex = Integer.parseInt(deviceId);
            cameraName = deviceNames[cameraIndex];
        } catch (Exception e) {
            Log.d(ASHISH, "createVideoCapturer: failed to find device with id: " + deviceId);
            Log.d(TAG, "failed to find device with id: " + deviceId);
        }

        // If deviceId is specified, then it takes precedence over facingMode.
        if (cameraName != null) {
            VideoCapturer videoCapturer = cameraEnumerator.createCapturer(cameraName, cameraEventsHandler);
            String message = "Create user-specified camera " + cameraName;
            if (videoCapturer != null) {
                Log.d(ASHISH, message + " succeeded");
                Log.d(TAG, message + " succeeded");
                this.isFrontFacing = cameraEnumerator.isFrontFacing(cameraName);
                this.currentDeviceId = String.valueOf(cameraIndex);
                return new CreateCapturerResult(cameraIndex, cameraName, videoCapturer);
            } else {
                // fallback to facingMode
                Log.d(ASHISH, message + " failed");
                Log.d(TAG, message + " failed");
                failedDevices.add(cameraName);
            }
        }

        // Otherwise, use facingMode (defaulting to front/user facing).
        final boolean isFrontFacing = facingMode == null || facingMode.equals("user");
        cameraIndex = -1;
        for (String name : deviceNames) {
            cameraIndex++;
            if (failedDevices.contains(name)) {
                continue;
            }
            if (cameraEnumerator.isFrontFacing(name) != isFrontFacing) {
                continue;
            }
            VideoCapturer videoCapturer = cameraEnumerator.createCapturer(name, cameraEventsHandler);
            String message = "Create camera " + name;
            if (videoCapturer != null) {
                Log.d(ASHISH, message + " succeeded");
                Log.d(TAG, message + " succeeded");
                this.isFrontFacing = cameraEnumerator.isFrontFacing(name);
                this.currentDeviceId = String.valueOf(cameraIndex);
                return new CreateCapturerResult(cameraIndex, name, videoCapturer);
            } else {
                Log.d(ASHISH, message + " failed");
                Log.d(TAG, message + " failed");
                failedDevices.add(name);
            }
        }

        cameraIndex = -1;
        // Fallback to any available camera.
        for (String name : deviceNames) {
            cameraIndex++;
            if (!failedDevices.contains(name)) {
                VideoCapturer videoCapturer = cameraEnumerator.createCapturer(name, cameraEventsHandler);
                String message = "Create fallback camera " + name;
                if (videoCapturer != null) {
                    Log.d(ASHISH, message + " succeeded");
                    Log.d(TAG, message + " succeeded");
                    this.isFrontFacing = cameraEnumerator.isFrontFacing(name);
                    this.currentDeviceId = String.valueOf(cameraIndex);
                    return new CreateCapturerResult(cameraIndex, name, videoCapturer);
                } else {
                    Log.d(ASHISH, message + " failed");
                    Log.d(TAG, message + " failed");
                    failedDevices.add(name);
                }
            }
        }

        currentDeviceId = null;
        Log.w(ASHISH, "Unable to identify a suitable camera.");
        Log.w(TAG, "Unable to identify a suitable camera.");

        return null;
    }

    private static class CreateCapturerResult {
        public final int cameraIndex;
        public final String cameraName;
        public final VideoCapturer videoCapturer;

        public CreateCapturerResult(int cameraIndex, String cameraName, VideoCapturer videoCapturer) {
            this.cameraIndex = cameraIndex;
            this.cameraName = cameraName;
            this.videoCapturer = videoCapturer;
        }
    }
}
