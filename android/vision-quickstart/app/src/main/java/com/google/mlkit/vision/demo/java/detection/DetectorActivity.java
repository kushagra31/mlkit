/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.vision.demo.java.detection;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.fxn.BubbleTabBar;
import com.fxn.OnBubbleClickListener;
import com.google.android.material.navigation.NavigationView;

import com.google.mlkit.common.model.LocalModel;
import com.google.mlkit.vision.demo.R;
import com.google.mlkit.vision.demo.java.LivePreviewActivity;
import com.google.mlkit.vision.demo.java.barcodescanner.BarcodeScannerProcessor;
import com.google.mlkit.vision.demo.java.detection.customview.OverlayView;
import com.google.mlkit.vision.demo.java.detection.customview.OverlayView.DrawCallback;
import com.google.mlkit.vision.demo.java.detection.env.BorderedText;
import com.google.mlkit.vision.demo.java.detection.env.ImageUtils;
import com.google.mlkit.vision.demo.java.detection.env.Logger;
import com.google.mlkit.vision.demo.java.detection.tflite.Classifier;
import com.google.mlkit.vision.demo.java.detection.tflite.YoloV4Classifier;
import com.google.mlkit.vision.demo.java.detection.tracking.MultiBoxTracker;
import com.google.mlkit.vision.demo.java.facedetector.FaceDetectorProcessor;
import com.google.mlkit.vision.demo.java.labeldetector.LabelDetectorProcessor;
import com.google.mlkit.vision.demo.java.objectdetector.ObjectDetectorProcessor;
import com.google.mlkit.vision.demo.java.posedetector.PoseDetectorProcessor;
import com.google.mlkit.vision.demo.java.textdetector.TextRecognitionProcessor;
import com.google.mlkit.vision.demo.preference.PreferenceUtils;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions;
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
    private static final Logger LOGGER = new Logger();

    private static final int TF_OD_API_INPUT_SIZE = 416;
    private static final boolean TF_OD_API_IS_QUANTIZED = true;
    private static final String TF_OD_API_MODEL_FILE = "custom_models/yolov4-416-int8.tflite";

    private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/custom_models/coco.names";

    private static final DetectorMode MODE = DetectorMode.TF_OD_API;
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
    private static final boolean MAINTAIN_ASPECT = false;
    private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final float TEXT_SIZE_DIP = 10;
    private Integer sensorOrientation;

    private Classifier detector;

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private MultiBoxTracker tracker;
    TextToSpeech textToSpeechSystem;

    private BorderedText borderedText;


    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {

        final ImageView toolbar1 = (ImageView) findViewById(R.id.imageView2);
        toolbar1.bringToFront();
        final DrawerLayout drawer =  findViewById(R.id.drawer_layout);
        NavigationView nav=findViewById(R.id.nav_view);
        final BubbleTabBar bubbleTabBar =findViewById(R.id.bubbleTabBar);
        bubbleTabBar.addBubbleListener(new OnBubbleClickListener() {
            @Override
            public void onBubbleClick(int i) {
                if(i==R.id.more){

                    Intent intent = new Intent(getApplicationContext(), LivePreviewActivity.class);
                    startActivity(intent);
                }
            }
        });
        toolbar1.setOnClickListener(view -> drawer.openDrawer(GravityCompat.START));

        final float textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new MultiBoxTracker(this);

        int cropSize = TF_OD_API_INPUT_SIZE;

        try {
            detector =
                    YoloV4Classifier.create(
                            getAssets(),
                            TF_OD_API_MODEL_FILE,
                            TF_OD_API_LABELS_FILE,
                            TF_OD_API_IS_QUANTIZED);
//            detector = TFLiteObjectDetectionAPIModel.create(
//                    getAssets(),
//                    TF_OD_API_MODEL_FILE,
//                    TF_OD_API_LABELS_FILE,
//                    TF_OD_API_INPUT_SIZE,
//                    TF_OD_API_IS_QUANTIZED);
            cropSize = TF_OD_API_INPUT_SIZE;
        } catch (final IOException e) {
            e.printStackTrace();
            LOGGER.e(e, "Exception initializing classifier!");
            Toast toast =
                    Toast.makeText(
                            getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();


        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);



        tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
    }

    @Override
    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }

        runInBackground(
                () -> {
                    LOGGER.i("Running detection on image " + currTimestamp);
                    final long startTime = SystemClock.uptimeMillis();
                    final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
                    lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                    Log.e("CHECK", "run: " + results.size());

                    cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                    final Canvas canvas1 = new Canvas(cropCopyBitmap);
                    final Paint paint = new Paint();
                    paint.setColor(Color.YELLOW);
                    paint.setStyle(Style.STROKE);
                    paint.setStrokeWidth(2.0f);

                    float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                    switch (MODE) {
                        case TF_OD_API:
                            minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                            break;
                    }

                    final List<Classifier.Recognition> mappedRecognitions =
                            new LinkedList<Classifier.Recognition>();

                    for (final Classifier.Recognition result : results) {
                        final RectF location = result.getLocation();
                        if (location != null && result.getConfidence() >= minimumConfidence) {
                            canvas1.drawRect(location, paint);

                            cropToFrameTransform.mapRect(location);

                            result.setLocation(location);
                            mappedRecognitions.add(result);

                            textToSpeechSystem = new TextToSpeech(DetectorActivity.this, new TextToSpeech.OnInitListener() {
                                @Override
                                public void onInit(int status) {
                                        if (status == TextToSpeech.SUCCESS) {
                                            float focallenght;
                                            final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                                            try {
                                                CameraCharacteristics m=manager.getCameraCharacteristics(chooseCamera());
                                                focallenght= Objects.requireNonNull(m.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))[0];
                                                SizeF sensorsize =m.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                                                String textToSay = result.getTitle();
                                                String[] s=textToSay.split(" #");
                                                int a = Integer.parseInt(s[1]);
                                                System.out.println(s[0]+a);
                                                assert sensorsize != null;
                                                float distance = (focallenght*a*getDesiredPreviewFrameSize().getHeight())
                                                        /
                                                        (result.getLocation().height()*sensorsize.getHeight());
                                                textToSpeechSystem.speak(s[0]+"is located at"+ Math.round(distance)+"centi meters", TextToSpeech.QUEUE_ADD, null);
                                            } catch (CameraAccessException e) {
                                                e.printStackTrace();
                                            }



                                        }



                                }
                            });




                        }
                    }

                    tracker.trackResults(mappedRecognitions, currTimestamp,DetectorActivity.this);


                    computingDetection = false;


                });
    }

    @Override
    protected int getLayoutId() {
        return R.layout.tfe_od_camera_connection_fragment_tracking;
    }

    @Override
    public Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }



    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum DetectorMode {
        TF_OD_API;
    }

    @Override
    protected void setUseNNAPI(final boolean isChecked) {
        runInBackground(() -> detector.setUseNNAPI(isChecked));
    }

    @Override
    protected void setNumThreads(final int numThreads) {
        runInBackground(() -> detector.setNumThreads(numThreads));
    }
}
