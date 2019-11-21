package com.hanlab.tsm;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;

public class MainActivity extends AppCompatActivity {
    private final String TAG = "MainActivity";

    private String[] app_permissions = {
            Manifest.permission.CAMERA
    };

    private BaseLoaderCallback openCVLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    Log.i(TAG, "OpenCV loaded");
                    if (checkPermissions())
                        launchTSM();
                }
                break;
                default: {
                    Log.e(TAG, "Error loading OpenCV");
                    finish();
                }
                break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (OpenCVLoader.initDebug()) {
            if (checkPermissions())
                launchTSM();
        } else {
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, openCVLoaderCallback);
        }
    }

    private void launchTSM() {
        Intent intent = new Intent(this, GestureActivity.class);
        startActivity(intent);
    }

    private boolean checkPermissions() {
        boolean hasAll = true;
        for (String permission : this.app_permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                hasAll = false;
            }
        }

        if (!hasAll) {
            requestPermissions(app_permissions, 0);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        assert permissions == app_permissions;

        boolean granted = true;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) granted = false;

        if (!granted) {
            requestPermissions(app_permissions, 1);
        } else {
            launchTSM();
        }
    }
}
