package com.hanlab.tsm;

import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayDeque;
import java.util.Deque;

public class GestureActivity extends AppCompatActivity implements Model.ModelCallback, CameraBridgeViewBase.CvCameraViewListener2 {
    private final String TAG = "GestureActivity";
    private final int CAP_W = 320;
    private final int CAP_H = 240;

    private Model model;
    private CameraBridgeViewBase camera;
    private TextView labelView;
    private Handler labelHandler;
    private boolean cameraReady = false;
    private boolean modelReady = false;
    private Mat rawFrame;
    private Mat rgbFrame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        this.getSupportActionBar().hide();

        setContentView(R.layout.activity_gesture);

        this.model = new Model(this, this);
        this.model.load(Model.MODEL_TYPE.JENKINS);

        labelView = (TextView) this.findViewById(R.id.modelLabel);

        labelHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                String label = msg.getData().getString("label");
                labelView.setText(label);
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();

        camera = (CameraBridgeViewBase) this.findViewById(R.id.cameraView);
        camera.setVisibility(SurfaceView.VISIBLE);
        camera.setMaxFrameSize(CAP_W, CAP_H);
        camera.setCameraIndex(CameraBridgeViewBase.CAMERA_ID_FRONT);
        camera.setCvCameraViewListener(this);
        camera.setCameraPermissionGranted();
        camera.enableView();
        camera.enableFpsMeter();
    }

    @Override
    protected void onPause() {
        super.onPause();
        camera.disableView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        camera.enableView();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        camera.disableView();
    }

    @Override
    public void modelLoadedCallback() {
        modelReady = true;
        Log.i(TAG, "Model Loaded");
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        cameraReady = true;
        rawFrame = new Mat(height, width, CvType.CV_8UC4);
        rgbFrame = new Mat(height, width, CvType.CV_8UC3);
        Log.i(TAG, "Camera Ready");
    }

    @Override
    public void onCameraViewStopped() {
        rawFrame.release();
        rgbFrame.release();
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        rawFrame = inputFrame.rgba();
        // Rotate if portrait
        if (this.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            Size origSize = rawFrame.size();
            //Core.flip(rawFrame, rawFrame, 1);
            Core.transpose(rawFrame, rawFrame);
            Imgproc.resize(rawFrame, rawFrame, origSize);
        } else {
            Core.flip(rawFrame, rawFrame, 1);
        }
//        byte[] marker = {(byte)255, 0, 0, (byte)255};
//        for (int i = 0; i < CAP_W; i++) {
//            Log.i(TAG, ""+rawFrame.type());
//            rawFrame.put(0, i, marker);
//        }
        Imgproc.cvtColor(rawFrame, rgbFrame, Imgproc.COLOR_RGBA2BGR, 3);

        if (modelReady) {
            int label_idx = model.process(rgbFrame);
            String label = model.getLabel(label_idx);

            Message msg = Message.obtain();
            Bundle data = new Bundle();
            data.putString("label", label);
            msg.setData(data);
            labelHandler.sendMessage(msg);
        }

        return rawFrame;
    }
}
