package com.oney.WebRTCModule.deepar;

import androidx.camera.core.CameraSelector;

import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;

import com.oney.WebRTCModule.ReactBridgeUtil;

/**
 * Immutable DeepAR capture settings normalized from getUserMedia video constraints.
 */
public class DeepARCaptureConfig {
    public static final String SOURCE_NAME = "deepar";
    private static final int MAX_FRAME_PIXELS = 1280 * 720;
    private static final int MAX_FPS = 30;
    private static final double ASPECT_RATIO_16_9 = 16.0 / 9.0;
    private static final double ASPECT_RATIO_4_3 = 4.0 / 3.0;

    private final String licenseKey;
    private final int lensFacing;
    private final int width;
    private final int height;
    private final int frameRate;
    private final String effectPath;

    public DeepARCaptureConfig(String licenseKey, int lensFacing, int width, int height, int frameRate, String effectPath) {
        this.licenseKey = licenseKey;
        this.lensFacing = lensFacing;
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.effectPath = effectPath;
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

    public String getEffectPath() {
        return effectPath;
    }

    public static String normalizeEffectPath(String rawPath) {
        if (rawPath == null) {
            return null;
        }

        String path = rawPath.trim();
        if (path.isEmpty()) {
            return null;
        }

        if (path.startsWith("file://") || path.startsWith("content://")) {
            return path;
        }

        if (path.startsWith("/android_asset/")) {
            return "file://" + path;
        }

        if (path.startsWith("android_asset/")) {
            return "file:///" + path;
        }

        if (path.startsWith("/")) {
            return path;
        }

        return path;
    }

    private static int ensureEven(int value) {
        int positive = Math.max(2, value);
        return (positive % 2 == 0) ? positive : positive - 1;
    }

    private static int[] normalizeSizePreservingAspect(int width, int height) {
        int safeWidth = ensureEven(width);
        int safeHeight = ensureEven(height);
        long requestedPixels = (long) safeWidth * (long) safeHeight;

        if (requestedPixels <= MAX_FRAME_PIXELS) {
            return new int[] { safeWidth, safeHeight };
        }

        double scale = Math.sqrt((double) MAX_FRAME_PIXELS / (double) requestedPixels);
        int scaledWidth = ensureEven((int) Math.floor(safeWidth * scale));
        int scaledHeight = ensureEven((int) Math.floor(safeHeight * scale));

        return new int[] { scaledWidth, scaledHeight };
    }

    private static int[] normalizeCameraFriendlySize(int width, int height) {
        int safeWidth = ensureEven(width);
        int safeHeight = ensureEven(height);
        double requestedAspect = (double) safeWidth / (double) safeHeight;
        double normalizedAspect = Math.abs(requestedAspect - ASPECT_RATIO_16_9) <= Math.abs(requestedAspect - ASPECT_RATIO_4_3)
                ? ASPECT_RATIO_16_9
                : ASPECT_RATIO_4_3;

        int normalizedHeight = ensureEven((int) Math.round(safeWidth / normalizedAspect));
        if (normalizedHeight <= 0) {
            normalizedHeight = safeHeight;
        }

        return normalizeSizePreservingAspect(safeWidth, normalizedHeight);
    }

    private static int readIntFromMap(ReadableMap map, String key, int fallback) {
        if (map == null || !map.hasKey(key)) {
            return fallback;
        }

        ReadableType type = map.getType(key);
        if (type == ReadableType.Number) {
            return Math.max(1, map.getInt(key));
        }

        if (type == ReadableType.Map) {
            ReadableMap nested = map.getMap(key);
            if (nested != null) {
                if (nested.hasKey("exact") && nested.getType("exact") == ReadableType.Number) {
                    return Math.max(1, nested.getInt("exact"));
                }
                if (nested.hasKey("ideal") && nested.getType("ideal") == ReadableType.Number) {
                    return Math.max(1, nested.getInt("ideal"));
                }
                if (nested.hasKey("max") && nested.getType("max") == ReadableType.Number) {
                    return Math.max(1, nested.getInt("max"));
                }
                if (nested.hasKey("min") && nested.getType("min") == ReadableType.Number) {
                    return Math.max(1, nested.getInt("min"));
                }
            }
        }

        return fallback;
    }

    private static int readFrameRate(ReadableMap videoConstraints) {
        int requestedFps = readIntFromMap(videoConstraints, "frameRate", MAX_FPS);
        return Math.min(Math.max(1, requestedFps), MAX_FPS);
    }

    public static boolean isDeepARSource(ReadableMap videoConstraints) {
        android.util.Log.d("ASHISH", "isDeepARSource called with: " + videoConstraints);
        String source = ReactBridgeUtil.getMapStrValue(videoConstraints, "source");
        android.util.Log.d("ASHISH", "isDeepARSource: source field is '" + source + "'");
        if (SOURCE_NAME.equalsIgnoreCase(source)) {
            android.util.Log.d("ASHISH", "isDeepARSource: Matched source='deepar', returning true");
            return true;
        }

        boolean hasDeepAR = videoConstraints.hasKey("deepAR") && videoConstraints.getType("deepAR") == com.facebook.react.bridge.ReadableType.Map;
        android.util.Log.d("ASHISH", "isDeepARSource: hasKey('deepAR') && type==Map: " + hasDeepAR);
        return hasDeepAR;
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

        int width = Math.max(2, readIntFromMap(videoConstraints, "width", 1280));
        int height = Math.max(2, readIntFromMap(videoConstraints, "height", 720));
        int[] normalizedSize = normalizeCameraFriendlySize(width, height);
        width = normalizedSize[0];
        height = normalizedSize[1];
        int frameRate = readFrameRate(videoConstraints);
        android.util.Log.d("ASHISH", "DeepAR effective capture config width=" + width + " height=" + height + " fps=" + frameRate);

        String effectPath = null;
        if (deepARMap != null) {
            effectPath = ReactBridgeUtil.getMapStrValue(deepARMap, "effectPath");
            if (effectPath == null || effectPath.trim().isEmpty()) {
                effectPath = ReactBridgeUtil.getMapStrValue(deepARMap, "effect");
            }
            if (effectPath == null || effectPath.trim().isEmpty()) {
                effectPath = ReactBridgeUtil.getMapStrValue(deepARMap, "effectName");
            }
        }
        if (effectPath == null || effectPath.trim().isEmpty()) {
            effectPath = ReactBridgeUtil.getMapStrValue(videoConstraints, "effectPath");
        }
        if (effectPath == null || effectPath.trim().isEmpty()) {
            effectPath = ReactBridgeUtil.getMapStrValue(videoConstraints, "effect");
        }
        if (effectPath == null || effectPath.trim().isEmpty()) {
            effectPath = ReactBridgeUtil.getMapStrValue(videoConstraints, "effectName");
        }
        effectPath = normalizeEffectPath(effectPath);

        if (effectPath == null) {
            android.util.Log.w("ASHISH", "No DeepAR effect provided in constraints. DeepAR will run with no effect.");
        } else {
            android.util.Log.d("ASHISH", "Resolved DeepAR effectPath: " + effectPath);
        }

        return new DeepARCaptureConfig(licenseKey, lensFacing, width, height, frameRate, effectPath);
    }
}
