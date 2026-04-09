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
        super(constraints.getInt("width"), constraints.getInt("height"), constraints.getInt("frameRate"));
        this.activity = activity;
        this.config = DeepARCaptureConfig.fromConstraints(constraints);
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
