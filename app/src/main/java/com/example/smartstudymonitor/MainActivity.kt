package com.example.smartstudymonitor

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvEyeRatio: TextView
    private lateinit var overlayView: OverlayView
    private lateinit var cameraExecutor: ExecutorService
    
    private var mediaPlayer: MediaPlayer? = null

    private var currentAction: String = ""
    private var actionStartTime: Long = 0L
    private var lastSoundPlayTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tvEyeRatio = findViewById(R.id.tvEyeRatio)
        overlayView = findViewById(R.id.overlayView)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            // Face Landmarks Mesh Enabled
            val faceOpts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
            val faceDetector = FaceDetection.getClient(faceOpts)

            // Object Detection
            val objOpts = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
            val objectDetector = ObjectDetection.getClient(objOpts)

            @Suppress("DEPRECATION")
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        @OptIn(ExperimentalGetImage::class)
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            val currentTime = SystemClock.elapsedRealtime()

                            // Object Detection (Phone)
                            objectDetector.process(image)
                                .addOnSuccessListener { objects ->
                                    val phoneObjects = mutableListOf<DetectedObject>()
                                    var isHoldingPhone = false

                                    for (obj in objects) {
                                        for (label in obj.labels) {
                                            // Matching multiple phone tags & fallback logic
                                            val tag = label.text.lowercase()
                                            if (tag.contains("phone") || tag.contains("mobile") || tag.contains("cell") || tag.contains("electronic")) {
                                                isHoldingPhone = true
                                                phoneObjects.add(obj)
                                            }
                                        }
                                    }

                                    // Process Face Data
                                    faceDetector.process(image)
                                        .addOnSuccessListener { faces ->
                                            overlayView.setResults(faces, phoneObjects)

                                            if (isHoldingPhone) {
                                                triggerVoiceAction("PUT THE PHONE AWAY!", R.raw.put_phone, "PHONE", currentTime)
                                            } else if (faces.isEmpty()) {
                                                // Face covered or gone
                                                triggerVoiceAction("DON'T COVER YOUR FACE!", R.raw.cover_face, "FACE_COVERED", currentTime)
                                            } else {
                                                val face = faces[0]
                                                val leftOpen = face.leftEyeOpenProbability ?: -1f
                                                val rightOpen = face.rightEyeOpenProbability ?: -1f
                                                val rotY = face.headEulerAngleY

                                                if (leftOpen != -1f && rightOpen != -1f) {
                                                    val avgRatio = (leftOpen + rightOpen) / 2.0f
                                                    val ratioPercentage = (avgRatio * 100).toInt()

                                                    if (Math.abs(rotY) > 35) {
                                                        triggerVoiceAction("DON'T COVER YOUR FACE!", R.raw.cover_face, "FACE_COVERED", currentTime)
                                                    } else if (avgRatio < 0.2f) { // Sleeping
                                                        triggerVoiceAction("WAKE UP & STUDY!", R.raw.wake_up, "SLEEPING", currentTime)
                                                    } else { // Normal Studying
                                                        currentAction = "STUDYING"
                                                        actionStartTime = 0L
                                                        runOnUiThread {
                                                            tvEyeRatio.text = "Status: Studying 📖\nEye Ratio: $ratioPercentage%"
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                }
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                // Handle exception
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun triggerVoiceAction(statusText: String, soundResId: Int, actionTag: String, currentTime: Long) {
        runOnUiThread {
            tvEyeRatio.text = statusText
        }

        if (currentAction != actionTag) {
            currentAction = actionTag
            actionStartTime = currentTime
        } else {
            // Trigger voice immediately if 1.5s persistent distraction, and repeat every 5s
            if (currentTime - actionStartTime >= 1500L) {
                if (currentTime - lastSoundPlayTime >= 5000L) {
                    playVoicePack(soundResId)
                    lastSoundPlayTime = currentTime
                }
            }
        }
    }

    private fun playVoicePack(soundResId: Int) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, soundResId)
            mediaPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mediaPlayer?.release()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
