package com.hanlab.tsm;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ml.dmlc.tvm.Function;
import ml.dmlc.tvm.Module;
import ml.dmlc.tvm.NDArray;
import ml.dmlc.tvm.TVMContext;
import ml.dmlc.tvm.TVMType;
import ml.dmlc.tvm.TVMValue;

class Model {
    private final String TAG = "Model";
    private final int MODEL_IN_DIMS = 224;
    private final int MODEL_OUT_DIM = 27;
    private final int MODEL_CHANNELS = 3;
    private final long[][] MODEL_BUF_SIZES = {
            {1, 3, 56, 56},
            {1, 4, 28, 28},
            {1, 4, 28, 28},
            {1, 8, 14, 14},
            {1, 8, 14, 14},
            {1, 8, 14, 14},
            {1, 12, 14, 14},
            {1, 12, 14, 14},
            {1, 20, 7, 7},
            {1, 20, 7, 7}
    };
    private final String[] MODEL_LABELS = {
            "Doing other things",  // 0
            "Drumming Fingers",  // 1
            "No gesture",  // 2
            "Pulling Hand In",  // 3
            "Pulling Two Fingers In",  // 4
            "Pushing Hand Away",  // 5
            "Pushing Two Fingers Away",  // 6
            "Rolling Hand Backward",  // 7
            "Rolling Hand Forward",  // 8
            "Shaking Hand",  // 9
            "Sliding Two Fingers Down",  // 10
            "Sliding Two Fingers Left",  // 11
            "Sliding Two Fingers Right",  // 12
            "Sliding Two Fingers Up",  // 13
            "Stop Sign",  // 14
            "Swiping Down",  // 15
            "Swiping Left",  // 16
            "Swiping Right",  // 17
            "Swiping Up",  // 18
            "Thumb Down",  // 19
            "Thumb Up",  // 20
            "Turning Hand Clockwise",  // 21
            "Turning Hand Counterclockwise",  // 22
            "Zooming In With Full Hand",  // 23
            "Zooming In With Two Fingers",  // 24
            "Zooming Out With Full Hand",  // 25
            "Zooming Out With Two Fingers"  // 26
    };
    private static final int[] ILLEGAL_IDX = {7, 8, 21, 22, 3};

    public interface ModelCallback {
        void modelLoadedCallback();
    }
    public enum MODEL_TYPE {
        JENKINS,
    }

    private static final String[] _SUPPORTED_ARCHS_NAMES = new String[]{"x86_64", "arm64-v8a"};
    private static final String[] _SUPPORTED_ARCHS_LLVM = new String[]{"x86_64", "aarch64"};
    private static final  Map<String, String> SUPPORTED_ARCHS;

    private static final Map<MODEL_TYPE, String> MODEL_NAMES;
    private static final Map<MODEL_TYPE, String> MODEL_ASSETS;

    static {
        Map<String, String> temp_archs = new HashMap<>();
        for (int i = 0; i < _SUPPORTED_ARCHS_NAMES.length; i++) {
            temp_archs.put(_SUPPORTED_ARCHS_NAMES[i], _SUPPORTED_ARCHS_LLVM[i]);
        }
        SUPPORTED_ARCHS = Collections.unmodifiableMap(temp_archs);

        Map<MODEL_TYPE, String> tempNames = new HashMap<>();
        Map<MODEL_TYPE, String> tempAssetPrefixes = new HashMap<>();
        tempNames.put(MODEL_TYPE.JENKINS, "Jenkins");
        tempAssetPrefixes.put(MODEL_TYPE.JENKINS, "jenkins_tsm_tvm_%s-linux-android");
        MODEL_NAMES = Collections.unmodifiableMap(tempNames);
        MODEL_ASSETS = Collections.unmodifiableMap(tempAssetPrefixes);
    }


    public MODEL_TYPE model_type;
    private String arch_string;
    private Context ctx;
    private AssetManager assetManager;
    private boolean load_initiated = false;
    private ModelLoader modelLoader;
    private Module graphRuntime;
    private ModelCallback modelCallback;

    private Mat scaleMat;
    private float[] inputArray;
    private List<float[]> bufferArray;

    private final boolean REFINE_OUTPUT = true;
    private final boolean HISTORY_LOGIT = false;
    private final int MAX_HISTORY = 20;
    private final int MAX_HISTORY_LOGIT = 6;
    private List<Integer> history;
    private Deque<float[]> historyLogit;
    private int curMax;

    public Model(Context ctx, ModelCallback callback) {
        this.modelCallback = callback;

        // Extract supported architectures
        if (Build.SUPPORTED_64_BIT_ABIS.length > 0)
            arch_string = Build.SUPPORTED_64_BIT_ABIS[0];
        else
            arch_string = Build.SUPPORTED_32_BIT_ABIS[0];

        if (SUPPORTED_ARCHS.containsKey(arch_string)) {
            arch_string = SUPPORTED_ARCHS.get(arch_string);
        } else {
            throw new RuntimeException("Unsupported architecture: " + arch_string);
        }

        this.ctx = ctx;
        this.assetManager = ctx.getAssets();

        scaleMat = new Mat(MODEL_IN_DIMS, MODEL_IN_DIMS, CvType.CV_32FC3, new Scalar(255.0, 255.0, 255.0));
        inputArray = new float[MODEL_CHANNELS*MODEL_IN_DIMS*MODEL_IN_DIMS];
        bufferArray = new ArrayList<>();
        for (int i = 0; i < MODEL_BUF_SIZES.length; i++) {
            int size = (int)(MODEL_BUF_SIZES[i][0] * MODEL_BUF_SIZES[i][1] * MODEL_BUF_SIZES[i][2] * MODEL_BUF_SIZES[i][3]);
            bufferArray.add(new float[size]);

            NDArray[] bufferNDArrays = new NDArray[MODEL_BUF_SIZES.length];
            bufferNDArrays[i] = NDArray.empty(MODEL_BUF_SIZES[i], new TVMType("float32"));
            bufferArray.set(i, bufferNDArrays[i].asFloatArray());
        }

        history = new ArrayList<>();
        history.add(2);
        historyLogit = new ArrayDeque<>();
    }

    public void load(MODEL_TYPE type) {
        this.load_initiated = true;
        this.model_type = type;
        this.modelLoader = new ModelLoader();
        modelLoader.execute();
    }

    public String getLabel(int idx) {
        return MODEL_LABELS[idx];
    }

    public int process(Mat frame_rgb) {
        Imgproc.resize(frame_rgb, frame_rgb, new Size(MODEL_IN_DIMS, MODEL_IN_DIMS));
        frame_rgb.convertTo(frame_rgb, CvType.CV_32FC3);
        Core.divide(frame_rgb, scaleMat, frame_rgb, 1, CvType.CV_32FC3);
//        int[] sizes = {224, 224};
//        Mat test = new Mat(sizes, CvType.CV_32FC3, new Scalar(0, 0, 255.0));
//        Log.i(TAG, "null: " + test.get(0,0)[2]);
        //frame_rgb.get(0,0, inputArray);
        // transpose such that matrix is [channels, width, height]
        for (int i = 0; i < MODEL_IN_DIMS; i++) {
            for (int j = 0; j < MODEL_IN_DIMS; j++) {
                for (int k = 0; k < MODEL_CHANNELS; k++) {
                    inputArray[k*MODEL_IN_DIMS*MODEL_IN_DIMS + i*MODEL_IN_DIMS + j] = (float)frame_rgb.get(i,j)[k];
                }
            }
        }

        // Indexing test code
//        float[] x = new float[10];
//        for (int i = 0; i < 10; i++)
//            x[i] = inputArray[100350+i];
//        Log.i(TAG, "data: "+Arrays.toString(x));

        NDArray inputNDArray = NDArray.empty(new long[]{1, MODEL_CHANNELS, MODEL_IN_DIMS, MODEL_IN_DIMS}, new TVMType("float32"));
        NDArray[] bufferNDArrays = new NDArray[MODEL_BUF_SIZES.length];
        for (int i = 0; i < MODEL_BUF_SIZES.length; i++) {
            bufferNDArrays[i] = NDArray.empty(MODEL_BUF_SIZES[i], new TVMType("float32"));
            bufferNDArrays[i].copyFrom(bufferArray.get(i));
        }
        NDArray outputNDArray = NDArray.empty(new long[]{1, MODEL_OUT_DIM}, new TVMType("float32"));

        // Setup inputs
        inputNDArray.copyFrom(inputArray);
        Function setInputFunc = graphRuntime.getFunction("set_input");
        setInputFunc.pushArg(0).pushArg(inputNDArray).invoke();
        for (int i = 0; i < bufferNDArrays.length; i++) {
            setInputFunc.pushArg(i+1).pushArg(bufferNDArrays[i]).invoke();
        }
        inputNDArray.release();
        setInputFunc.release();

        // Run model
        graphRuntime.getFunction("run").invoke();

        // Get output
        Function getOutputFunc = graphRuntime.getFunction("get_output");
        getOutputFunc.pushArg(0).pushArg(outputNDArray).invoke();
        for(int i = 0; i < bufferNDArrays.length; i++) {
            getOutputFunc.pushArg(i+1).pushArg(bufferNDArrays[i]).invoke();
            bufferArray.set(i, bufferNDArrays[i].asFloatArray());
        }
        float[] output = outputNDArray.asFloatArray();
        outputNDArray.release();
        getOutputFunc.release();

        return processOutput(output);
    }

    private int processOutput(float[] outputs) {
        int max_idx = getMaxIdx(outputs);
        if (HISTORY_LOGIT)
            max_idx = updateHistoryLogit(outputs);

        curMax = max_idx;
        if (REFINE_OUTPUT)
            curMax = refineOutput(max_idx);
        return curMax;
    }

    private int getMaxIdx(float[] arr) {
        int max_idx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[max_idx])
                max_idx = i;
        }
        return max_idx;
    }

    private int updateHistoryLogit(float[] outputs) {
        historyLogit.add(outputs);
        if (historyLogit.size() > MAX_HISTORY_LOGIT)
            historyLogit.remove(0);
        float[] temp_hist = new float[outputs.length];
        for (float[] out : historyLogit) {
            for (int i = 0; i < outputs.length; i++) {
                temp_hist[i] += out[i];
            }
        }
        Log.i(TAG, ""+Arrays.toString(temp_hist));

        return getMaxIdx(temp_hist);
    }

    private int refineOutput(int idx) {
        for (int illegal : ILLEGAL_IDX) {
            if (idx == illegal)
                idx = history.get(history.size()-1);
        }

        if (idx == 0)
            idx = 2;

        if (idx != history.get(history.size()-1)) {
            if (history.size() > 1 && !history.get(history.size() - 1).equals(history.get(history.size() - 2)))
                idx = history.get(history.size()-1);
        }

        history.add(idx);
        if (history.size() > MAX_HISTORY)
            history.remove(0);

        return idx;
    }

    private byte[] readBytes(File file) throws IOException {
        InputStream is = assetManager.open(file.toString());
        byte[] target = new byte[is.available()];
        is.read(target);
        return target;
    }

    private class ModelLoader extends AsyncTask<Void, Void, Boolean> {
        private static final String TAG = "Model.ModelLoader";
        private AlertDialog progressDialog;
        private AlertDialog failDialog;

        public ModelLoader() {
            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setPadding(0, 30, 15, 30);
            layout.setGravity(Gravity.CENTER);

            ProgressBar progressBar = new ProgressBar(ctx);
            progressBar.setPadding(0, 0, 30, 0);
            progressBar.setIndeterminate(true);

            TextView textView = new TextView(ctx);
            String load_msg = ctx.getString(R.string.load_model);
            textView.setText(load_msg + MODEL_NAMES.get(model_type));
            textView.setTextSize(20);

            layout.addView(progressBar);
            layout.addView(textView);

            progressDialog = new AlertDialog.Builder(ctx).setCancelable(false).setView(layout).create();

            String load_fail_msg = ctx.getString(R.string.fail_load_model);
            failDialog = new AlertDialog.Builder(ctx).setMessage(load_fail_msg + MODEL_NAMES.get(model_type)).create();
        }

        // Attempts to load TVM Model from assets
        @Override
        protected Boolean doInBackground(Void... args) {
            File graphFile = new File(String.format(MODEL_ASSETS.get(model_type), arch_string) + ".json");
            File libFile = new File(String.format(MODEL_ASSETS.get(model_type), arch_string) + ".so");
            File paramFile = new File(String.format(MODEL_ASSETS.get(model_type), arch_string) + ".params");

            String modelGraph;
            Module modelLib;
            File tempModelLib;
            byte[] modelParams;
            try {
                Log.i(TAG, "Loading " + graphFile.toString());
                modelGraph = new String(readBytes(graphFile));
                Log.i(TAG, "Loading " + paramFile.toString());
                modelParams = readBytes(paramFile);

                Log.i(TAG, "Loading " + libFile.toString());
                byte[] libBytes = readBytes(libFile);
                File tempDir = File.createTempFile("tsm_model_lib_"+MODEL_NAMES.get(model_type), "");
                if (!tempDir.isDirectory() && tempDir.canWrite())
                    tempDir.delete();
                if (!tempDir.exists())
                    tempDir.mkdirs();
                tempModelLib = Paths.get(tempDir.toString(), libFile.toString()).toFile();
                FileOutputStream os = new FileOutputStream(tempModelLib);
                os.write(libBytes);
                os.close();
            } catch (IOException e) {
                Log.e(TAG, "Error loading graph " + MODEL_NAMES.get(model_type), e);
                return false;
            }

            TVMContext tvmCtx = TVMContext.cpu();
            modelLib = Module.load(tempModelLib.toString());

            Function tvmFnCreateRuntime = Function.getFunction("tvm.graph_runtime.create");
            TVMValue tvmFnCreateRuntimeRes = tvmFnCreateRuntime.pushArg(modelGraph)
                                                      .pushArg(modelLib)
                                                      .pushArg(tvmCtx.deviceType)
                                                      .pushArg(tvmCtx.deviceId)
                                                      .invoke();

            graphRuntime = tvmFnCreateRuntimeRes.asModule();

            Function tvmFnLoadParams = graphRuntime.getFunction("load_params");
            tvmFnLoadParams.pushArg(modelParams).invoke();

            modelLib.release();
            tvmFnLoadParams.release();

            return true;
        }

        @Override
        protected void onPreExecute() {
            progressDialog.show();
        }

        @Override
        protected void onPostExecute(Boolean created) {
            if (progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            if (!created) {
                // On fail should return to MainActivity
                failDialog.show();
            }

            modelCallback.modelLoadedCallback();
        }
    }
}
