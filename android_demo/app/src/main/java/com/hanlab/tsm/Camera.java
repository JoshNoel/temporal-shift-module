package com.hanlab.tsm;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.ArrayList;

public class Camera {
    private static final String TAG = "com.hanlab.tsm.Camera";

    // Callback when frame is ready
    public interface CameraCallback {
        void imageReadyCallback(byte[] rgbData);
        void cameraReadyCallback();
    }

    public static class CameraConfig {
        // Allows capture rates above 30 FPS
        private boolean highSpeed = false;
        private Surface previewView = null;
        private boolean captureRaw = false;
        private int h = 0;
        private int w = 0;

        public void setHighSpeed(boolean highSpeed) {
            this.highSpeed = highSpeed;
        }
        public void setPreviewView(Surface previewView) {
            this.previewView = previewView;
        }
        public void setCaptureRaw(boolean raw, int h, int w) {
            this.captureRaw = raw;
            if (raw) {
                this.h = h;
                this.w = w;
            } else {
                this.h = 0;
                this.w = 0;
            }
        }
    }

    private CameraCallback cameraCallback;
    private Handler handler;
    private HandlerThread handlerThread;

    private Context ctx;
    private CameraManager manager;
    private CameraController controller;
    private CaptureStateController captureStateController;
    private CaptureCallbackController captureCallbackController;
    private String activeCamID;
    private CameraDevice activeCam;
    private CameraConfig activeConfig;
    private CameraCaptureSession activeCaptureSession;

    // YUV -> RGB Conversion
    RenderScript renderScript;
    ScriptIntrinsicYuvToRGB renderScript_convert;
    Allocation rawImage_yuv;
    Allocation rawImage_rgb;

    public Camera(Context ctx, CameraCallback callback) {
        this.cameraCallback = callback;
        this.ctx = ctx;
        this.manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        this.controller = new CameraController();
        this.captureStateController = new CaptureStateController();
        this.captureCallbackController = new CaptureCallbackController();
        this.handlerThread = new HandlerThread("CameraBackground");
        this.handlerThread.start();
        this.handler = new Handler(this.handlerThread.getLooper());
    }

    public boolean openFrontCamera(CameraConfig config) {
        // Check camera permission
        if (!checkPermission())
            return false;

        String frontCamID = null;

        // Attempt to find and open front camera (see CameraController for callbacks)
        try {
            String[] availableCams = manager.getCameraIdList();
            for (String cam : availableCams) {
                CameraCharacteristics info = manager.getCameraCharacteristics(cam);
                if (info.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) {
                    boolean highSpeedAvailable = false;
                    for (int cap : info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)) {
                        if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) {
                            highSpeedAvailable = true;
                        }
                    }

                    if (!config.highSpeed || highSpeedAvailable)
                        frontCamID = cam;
                    break;
                }
            }

            if (frontCamID == null) {
                Log.d(TAG, "No front camera found.");
                return false;
            }

            this.activeConfig = config;
            manager.openCamera(frontCamID, this.controller, handler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening front camera.", e);
        }

        return true;
    }

    public boolean requestImage() {
        Log.i(TAG, "Image Requested");
        if (activeCaptureSession == null)
            return false;
        try {
            CaptureRequest.Builder req = activeCam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(rawImage_yuv.getSurface());
            req.addTarget(activeConfig.previewView);
            activeCaptureSession.capture(req.build(), captureCallbackController, this.handler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access failed on capture read", e);
            return false;
        }

        return true;
    }

    public void close() {
        Log.i(TAG, "Camera close");
        activeCamID = null;
        activeConfig = null;

        if (activeCam != null) {
            activeCam.close();
            activeCam = null;
        }
        if (activeCaptureSession != null) {
            activeCaptureSession.close();
            activeCaptureSession = null;
        }
        if (rawImage_yuv != null)
            rawImage_yuv.destroy();
        if (rawImage_rgb != null)
            rawImage_rgb.destroy();
        if (renderScript != null)
            renderScript.destroy();
        if (renderScript_convert != null)
            renderScript_convert.destroy();
    }

    private boolean checkPermission() {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Unable to open camera without CAMERA permission");
            return false;
        }
        return true;
    }

    private byte[] convert_YUV_to_RGBA(Allocation yuv) {
        assert renderScript_convert != null;
        assert yuv != null;
        yuv.ioReceive();
        renderScript_convert.setInput(yuv);
        renderScript_convert.forEach(rawImage_rgb);

        int size = activeConfig.h * activeConfig.w * 4;
        byte[] b_rgbData = new byte[size];
        float[] rgbData = new float[size];

        rawImage_rgb.copyTo(b_rgbData);

        return b_rgbData;
    }

    private class CameraController extends CameraDevice.StateCallback {
        private static final String TAG = "com.hanlab.tsm.Camera.CameraController";

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            activeCam = camera;
            activeCamID = camera.getId();

            int sessionType = activeConfig.highSpeed ? SessionConfiguration.SESSION_HIGH_SPEED : SessionConfiguration.SESSION_REGULAR;

            // Generate preview output and raw output for processing
            OutputConfiguration previewOutput = null;
            OutputConfiguration rawOutput = null;

            if (activeConfig.previewView != null)
                previewOutput = new OutputConfiguration(activeConfig.previewView);
            if (activeConfig.captureRaw) {
                renderScript = RenderScript.create(ctx);
                renderScript_convert = ScriptIntrinsicYuvToRGB.create(renderScript, Element.RGBA_8888(renderScript));

                Type.Builder yuv = new Type.Builder(renderScript, Element.U8(renderScript))
                                        .setX(activeConfig.w).setY(activeConfig.h)
                                        .setYuvFormat(ImageFormat.YUV_420_888);
                rawImage_yuv = Allocation.createTyped(renderScript, yuv.create(), Allocation.USAGE_IO_INPUT | Allocation.USAGE_SCRIPT);
                rawImage_yuv.setOnBufferAvailableListener(captureStateController);

                Type.Builder rgba = new Type.Builder(renderScript, Element.RGBA_8888(renderScript))
                        .setX(activeConfig.w).setY(activeConfig.h);
                rawImage_rgb = Allocation.createTyped(renderScript, rgba.create(), Allocation.USAGE_SCRIPT);

                rawOutput = new OutputConfiguration(rawImage_yuv.getSurface());
            }

            ArrayList<OutputConfiguration> outputs = new ArrayList<>();
            if (previewOutput != null)
                outputs.add(previewOutput);
            if (rawOutput != null)
                outputs.add(rawOutput);

            SessionConfiguration captureConfig = new SessionConfiguration(sessionType, outputs, ctx.getMainExecutor(), captureStateController);

            try {
                activeCam.createCaptureSession(captureConfig);
                Log.i(TAG, "Camera capture session created");
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to create camera session - " + activeCamID, e);
                close();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            close();
            Log.d(TAG, "com.hanlab.tsm.Camera disconnected - " + camera.getId());
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            close();
            Log.e(TAG, "com.hanlab.tsm.Camera encountered error " + error + " - " + camera.getId());
        }
    }

    private class CaptureStateController extends CameraCaptureSession.StateCallback implements Allocation.OnBufferAvailableListener {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            activeCaptureSession = session;
            cameraCallback.cameraReadyCallback();
            Log.i(TAG, "Camera capture session configured");
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            close();
            Log.e(TAG, "Camera capture session failed");
        }

        @Override
        public void onBufferAvailable(Allocation a) {
            cameraCallback.imageReadyCallback(convert_YUV_to_RGBA(a));
        }
    }

    private class CaptureCallbackController extends CameraCaptureSession.CaptureCallback {
        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest req, CaptureFailure res) {
            Log.i(TAG, "Request Failed - " + res.getReason());
            cameraCallback.imageReadyCallback(null);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest req, TotalCaptureResult res) {
            Log.i(TAG, "Request Complete");
        }
    }

}
