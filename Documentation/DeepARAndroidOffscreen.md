# DeepAR Offscreen Video Source (Android)

This integration replaces the default WebRTC camera capturer with a custom DeepAR capturer:

CameraX -> DeepAR (offscreen) -> WebRTC `VideoTrack`

## What Was Added

1. `android/src/main/java/com/oney/WebRTCModule/deepar/DeepARCaptureConfig.java`
- Parses DeepAR-specific constraints.
- Detects whether `video.source === "deepar"`.

2. `android/src/main/java/com/oney/WebRTCModule/deepar/DeepARVideoCaptureController.java`
- A capture controller used by `getUserMedia`.
- Creates `org.webrtc.DeepARVideoCapturer`.

3. `android/src/main/java/org/webrtc/DeepARVideoCapturer.java`
- Custom `VideoCapturer` implementation.
- Initializes DeepAR with:
  - `new DeepAR(context)`
  - `setLicenseKey(...)`
  - `initialize(...)`
- Enables offscreen rendering:
  - `setOffscreenRendering(width, height)`
- Uses CameraX `ImageAnalysis` and forwards frames via `deepAR.receiveFrame(...)`.
- Implements `AREventListener` and forwards processed frames from `frameAvailable(Image)` to WebRTC using `capturerObserver.onFrameCaptured(...)`.

4. `android/src/main/java/org/webrtc/DeepARFrameConverter.java`
- Converts DeepAR output `android.media.Image` to WebRTC `VideoFrame.I420Buffer`.
- Handles `YUV_420_888` and RGBA input.

## Existing WebRTC Integration Point Changed

- `android/src/main/java/com/oney/WebRTCModule/GetUserMediaImpl.java`
  - Existing camera path remains unchanged.
  - New branch selects DeepAR source when video constraints request it.

## Dependencies

Added to `android/build.gradle`:
- `androidx.camera:camera-core:1.3.4`
- `androidx.camera:camera-camera2:1.3.4`
- `androidx.camera:camera-lifecycle:1.3.4`
- `ai.deepar.ar:deepar:5.6.0` (override with `ext.deepArDependency` if needed)

If Gradle fails with `403 Forbidden` while resolving `ai.deepar.ar:deepar`, configure auth/local overrides in your app `android/build.gradle`:

```gradle
buildscript {
  ext {
    // Optional: private DeepAR Maven credentials.
    deepArMavenUsername = "<username>"
    deepArMavenPassword = "<password>"

    // Optional: custom coordinate/version.
    // deepArDependency = "ai.deepar.ar:deepar:5.6.0"

    // Optional: local AAR fallback (recommended when Maven access is blocked).
    // deepArAarPath = "${rootDir}/../node_modules/react-native-webrtc/android/libs/deepar-5.6.0.aar"
  }
}
```

## JS Usage Example

```ts
import { mediaDevices } from 'react-native-webrtc';

const stream = await mediaDevices.getUserMedia({
  audio: true,
  video: {
    source: 'deepar',
    width: 1280,
    height: 720,
    frameRate: 30,
    facingMode: 'user',
    deepAR: {
      licenseKey: 'b40d07522b8976d959e1ac9bc137a77f67a422aa77d6078efc67249e6f1db63976c2442c340d591c',
      lensFacing: 'front'
    }
  }
});

// Use this stream with RTCPeerConnection like any normal camera stream.
```

## Notes For Production Hardening

- Current RGBA -> I420 conversion is CPU-based Java conversion. For strict 30 FPS on lower-end devices, move conversion to native libyuv/OpenGL path.
- Current implementation avoids on-screen rendering and uses DeepAR offscreen processing only.
- To integrate with Jitsi Meet later, keep requesting tracks through a custom source flag and map that flag in your media acquisition layer.
