package org.webrtc;

import android.graphics.ImageFormat;
import android.media.Image;

import java.nio.ByteBuffer;

/**
 * Utility methods for converting DeepAR processed output frames into WebRTC buffers.
 */
public final class DeepARFrameConverter {
    private DeepARFrameConverter() {
    }

    public static VideoFrame.I420Buffer toI420(Image image) {
        int format = image.getFormat();
        if (format == ImageFormat.YUV_420_888) {
            return yuv420888ToI420(image);
        }

        return rgba8888ToI420(image);
    }

    private static VideoFrame.I420Buffer yuv420888ToI420(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        VideoFrame.I420Buffer i420 = JavaI420Buffer.allocate(width, height);

        Image.Plane[] planes = image.getPlanes();
        copyPlane(planes[0], i420.getDataY(), width, height, i420.getStrideY());
        copyPlane(planes[1], i420.getDataU(), width / 2, height / 2, i420.getStrideU());
        copyPlane(planes[2], i420.getDataV(), width / 2, height / 2, i420.getStrideV());

        return i420;
    }

    private static VideoFrame.I420Buffer rgba8888ToI420(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        VideoFrame.I420Buffer i420 = JavaI420Buffer.allocate(width, height);

        ByteBuffer rgba = image.getPlanes()[0].getBuffer();
        int rowStride = image.getPlanes()[0].getRowStride();
        int pixelStride = image.getPlanes()[0].getPixelStride();

        ByteBuffer y = i420.getDataY();
        ByteBuffer u = i420.getDataU();
        ByteBuffer v = i420.getDataV();
        int yStride = i420.getStrideY();
        int uStride = i420.getStrideU();
        int vStride = i420.getStrideV();

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int pixelOffset = row * rowStride + col * pixelStride;
                int r = rgba.get(pixelOffset) & 0xFF;
                int g = rgba.get(pixelOffset + 1) & 0xFF;
                int b = rgba.get(pixelOffset + 2) & 0xFF;

                int yValue = clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                y.put(row * yStride + col, (byte) yValue);
            }
        }

        for (int row = 0; row < height; row += 2) {
            for (int col = 0; col < width; col += 2) {
                int pixelOffset = row * rowStride + col * pixelStride;
                int r = rgba.get(pixelOffset) & 0xFF;
                int g = rgba.get(pixelOffset + 1) & 0xFF;
                int b = rgba.get(pixelOffset + 2) & 0xFF;

                int uValue = clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                int vValue = clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                u.put((row / 2) * uStride + (col / 2), (byte) uValue);
                v.put((row / 2) * vStride + (col / 2), (byte) vValue);
            }
        }

        return i420;
    }

    private static int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return value;
    }

    private static void copyPlane(
            Image.Plane plane,
            ByteBuffer out,
            int width,
            int height,
            int outStride) {
        ByteBuffer in = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        for (int row = 0; row < height; row++) {
            int inRowOffset = row * rowStride;
            int outRowOffset = row * outStride;
            for (int col = 0; col < width; col++) {
                out.put(outRowOffset + col, in.get(inRowOffset + col * pixelStride));
            }
        }
    }
}
