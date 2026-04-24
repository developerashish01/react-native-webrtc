package com.oney.WebRTCModule.deepar;

import android.app.Activity;

import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.oney.WebRTCModule.AbstractVideoCaptureController;

import org.webrtc.DeepARVideoCapturer;
import org.webrtc.VideoCapturer;

public class DeepARVideoCaptureController extends AbstractVideoCaptureController {
    private final Activity activity;
    private final DeepARCaptureConfig config;

    public DeepARVideoCaptureController(Activity activity, ReadableMap constraints) {
        this(activity, DeepARCaptureConfig.fromConstraints(constraints));
    }

    private DeepARVideoCaptureController(Activity activity, DeepARCaptureConfig config) {
        super(config.getWidth(), config.getHeight(), config.getFrameRate());
        this.activity = activity;
        this.config = config;
    }

    /**
     * Switches the DeepAR effect at runtime if the underlying capturer is available.
     * @param effectPath The path to the new DeepAR effect.
     */
    public void switchEffect(String effectPath) {
        if (videoCapturer instanceof DeepARVideoCapturer) {
            ((DeepARVideoCapturer) videoCapturer).switchEffect(effectPath);
        }
    }

    @Nullable
    @Override
    public String getDeviceId() {
        return DeepARCaptureConfig.SOURCE_NAME;
    }

    @Override
    public WritableMap getSettings() {
        WritableMap settings = super.getSettings();
        settings.putString("facingMode", config.getLensFacing() == androidx.camera.core.CameraSelector.LENS_FACING_BACK
                ? "environment"
                : "user");
        settings.putString("source", DeepARCaptureConfig.SOURCE_NAME);
        return settings;
    }

    @Override
    protected VideoCapturer createVideoCapturer() {
        DeepARVideoCapturer deepARVideoCapturer = new DeepARVideoCapturer(activity, config);
        deepARVideoCapturer.setCapturerEventsListener(() -> {
            if (capturerEventsListener != null) {
                capturerEventsListener.onCapturerEnded();
            }
        });
        return deepARVideoCapturer;
    }
}
