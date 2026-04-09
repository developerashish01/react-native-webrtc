package com.oney.WebRTCModule.deepar;

import androidx.camera.core.CameraSelector;

import com.facebook.react.bridge.ReadableMap;

import com.oney.WebRTCModule.ReactBridgeUtil;

/**
 * Immutable DeepAR capture settings normalized from getUserMedia video constraints.
 */
public class DeepARCaptureConfig {
    public static final String SOURCE_NAME = "deepar";

    private final String licenseKey;
    private final int lensFacing;
    private final int width;
    private final int height;
    private final int frameRate;

    public DeepARCaptureConfig(String licenseKey, int lensFacing, int width, int height, int frameRate) {
        this.licenseKey = licenseKey;
        this.lensFacing = lensFacing;
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
    }

    public String getLicenseKey() {
        return licenseKey;
    }

    public int getLensFacing() {
        return lensFacing;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFrameRate() {
        return frameRate;
    }

    public static boolean isDeepARSource(ReadableMap videoConstraints) {
        String source = ReactBridgeUtil.getMapStrValue(videoConstraints, "source");
        if (SOURCE_NAME.equalsIgnoreCase(source)) {
            return true;
        }

        return videoConstraints.hasKey("deepAR") && videoConstraints.getType("deepAR") == com.facebook.react.bridge.ReadableType.Map;
    }

    public static DeepARCaptureConfig fromConstraints(ReadableMap videoConstraints) {
        ReadableMap deepARMap = videoConstraints.hasKey("deepAR")
                ? videoConstraints.getMap("deepAR")
                : null;

        String licenseKey = ReactBridgeUtil.getMapStrValue(videoConstraints, "deepARLicenseKey");
        if (licenseKey == null && deepARMap != null) {
            licenseKey = ReactBridgeUtil.getMapStrValue(deepARMap, "licenseKey");
        }

        if (licenseKey == null || licenseKey.trim().isEmpty()) {
            throw new IllegalArgumentException("DeepAR video source requires deepAR.licenseKey or deepARLicenseKey.");
        }

        String facing = ReactBridgeUtil.getMapStrValue(videoConstraints, "facingMode");
        if (deepARMap != null) {
            String deepARFacing = ReactBridgeUtil.getMapStrValue(deepARMap, "lensFacing");
            if (deepARFacing != null) {
                facing = deepARFacing;
            }
        }

        int lensFacing = "environment".equalsIgnoreCase(facing) || "back".equalsIgnoreCase(facing)
                ? CameraSelector.LENS_FACING_BACK
                : CameraSelector.LENS_FACING_FRONT;

        int width = videoConstraints.getInt("width");
        int height = videoConstraints.getInt("height");
        int frameRate = videoConstraints.getInt("frameRate");

        return new DeepARCaptureConfig(licenseKey, lensFacing, width, height, frameRate);
    }
}
