package com.oney.WebRTCModule.deepar;

import androidx.camera.core.CameraSelector;

import com.facebook.react.bridge.ReadableMap;

import com.oney.WebRTCModule.ReactBridgeUtil;

/**
 * Immutable DeepAR capture settings normalized from getUserMedia video constraints.
 */
public class DeepARCaptureConfig {
    public static final String SOURCE_NAME = "deepar";
    private static final String ANDROID_ASSET_PREFIX = "file:///android_asset/";
    private static final String DEFAULT_EFFECT_FILENAME = "viking_helmet.deepar";
    private static final int MAX_FRAME_PIXELS = 960 * 540;
    private static final int MAX_FPS = 15;
        private static final int[][] STANDARD_4_3_SIZES = new int[][] {
            {960, 720},
            {640, 480},
            {480, 360},
            {320, 240}
        };

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

    private static String normalizeEffectPath(String rawPath) {
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

        // Desktop/host absolute paths do not exist on Android devices.
        // Fall back to loading by filename from app assets.
        if (path.startsWith("/")) {
            int slashIndex = path.lastIndexOf('/');
            if (slashIndex >= 0 && slashIndex + 1 < path.length()) {
                path = path.substring(slashIndex + 1);
            }
        }

        return ANDROID_ASSET_PREFIX + path;
    }

    private static int ensureEven(int value) {
        int positive = Math.max(2, value);
        return (positive % 2 == 0) ? positive : positive - 1;
    }

    private static int[] normalizeToFourThree(int width, int height) {
        boolean portrait = height > width;
        long requestPixels = (long) width * (long) height;
        long pixelBudget = Math.min((long) MAX_FRAME_PIXELS, requestPixels);

        int[] selected = STANDARD_4_3_SIZES[STANDARD_4_3_SIZES.length - 1];
        for (int[] candidate : STANDARD_4_3_SIZES) {
            long candidatePixels = (long) candidate[0] * (long) candidate[1];
            if (candidatePixels <= pixelBudget) {
                selected = candidate;
                break;
            }
        }

        int normalizedWidth = ensureEven(selected[0]);
        int normalizedHeight = ensureEven(selected[1]);
        if (portrait) {
            int temp = normalizedWidth;
            normalizedWidth = normalizedHeight;
            normalizedHeight = temp;
        }

        return new int[] { normalizedWidth, normalizedHeight };
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

        int width = Math.max(2, videoConstraints.getInt("width"));
        int height = Math.max(2, videoConstraints.getInt("height"));
        int[] normalizedSize = normalizeToFourThree(width, height);
        width = normalizedSize[0];
        height = normalizedSize[1];
        int frameRate = Math.min(videoConstraints.getInt("frameRate"), MAX_FPS);
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
            effectPath = ANDROID_ASSET_PREFIX + DEFAULT_EFFECT_FILENAME;
            android.util.Log.w("ASHISH", "No DeepAR effect provided in constraints, defaulting to: " + effectPath);
        } else {
            android.util.Log.d("ASHISH", "Resolved DeepAR effectPath: " + effectPath);
        }

        return new DeepARCaptureConfig(licenseKey, lensFacing, width, height, frameRate, effectPath);
    }
}
