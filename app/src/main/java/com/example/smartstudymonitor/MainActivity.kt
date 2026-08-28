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
    
    // 3-Second Distraction Timers
    private var activeDistractionState: String = "NONE"
    private var distractionStartTime: Long = 0L
    private val DISTRACTION_HOLD_TIME_MS = 3000L // 3 Seconds Buffer
    
    private var lastVoiceTime: Long = 0L
    private val VOICE_COOLDOWN_MS = 4000L

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

            val faceOpts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()
            val faceDetector = FaceDetection.getClient(faceOpts)

            val objOpts = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
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

                            objectDetector.process(image)
                                .addOnSuccessListener { objects ->
                                    val detectedPhones = mutableListOf<DetectedObject>()
                                    
                                    for (obj in objects) {
                                        val bounds = obj.boundingBox
                                        val objWidth = bounds.width().toFloat()
                                        val objHeight = bounds.height().toFloat()
                                        val aspectRatio = if (objWidth > 0) objHeight / objWidth else 0f
                                        
                                        var isStrictPhone = false
                                        for (label in obj.labels) {
                                            val text = label.text.lowercase()
                                            val confidence = label.confidence

                                            if ((text == "mobile phone" || text == "cell phone" || text == "telephone") && confidence > 0.5f) {
                                                isStrictPhone = true
                                                break
                                            }
                                        }

                                        if (isStrictPhone && (aspectRatio in 1.2f..2.5f || aspectRatio in 0.4f..0.8f)) {
                                            detectedPhones.add(obj)
                                        }
                                    }

                                    faceDetector.process(image)
                                        .addOnSuccessListener { faces ->
                                            overlayView.setResults(faces, detectedPhones, imageProxy.width, imageProxy.height)

                                            // Determine current frame state
                                            val currentState: String
                                            val soundResId: Int
                                            val statusMsg: String

                                            if (detectedPhones.isNotEmpty()) {
                                                currentState = "PHONE"
                                                soundResId = R.raw.put_phone
                                                statusMsg = "PUT THE PHONE AWAY!"
                                            } else if (faces.isEmpty()) {
                                                currentState = "COVER"
                                                soundResId = R.raw.cover_face
                                                statusMsg = "DON'T COVER YOUR FACE!"
                                            } else {
                                                val face = faces[0]
                                                val leftOpen = face.leftEyeOpenProbability ?: -1f
                                                val rightOpen = face.rightEyeOpenProbability ?: -1f
                                                val rotY = face.headEulerAngleY

                                                if (leftOpen != -1f && rightOpen != -1f) {
                                                    val avgRatio = (leftOpen + rightOpen) / 2.0f
                                                    val ratioPercentage = (avgRatio * 100).toInt()

                                                    if (Math.abs(rotY) > 35) {
                                                        currentState = "COVER"
                                                        soundResId = R.raw.cover_face
                                                        statusMsg = "DON'T COVER YOUR FACE!"
                                                    } else if (avgRatio < 0.2f) { // Closed Eyes Threshold
                                                        currentState = "SLEEP"
                                                        soundResId = R.raw.wake_up
                                                        statusMsg = "WAKE UP & STUDY!"
                                                    } else {
                                                        currentState = "NONE"
                                                        soundResId = 0
                                                        statusMsg = "Status: Studying 📖\nEye Ratio: $ratioPercentage%"
                                                    }
                                                } else {
                                                    currentState = "NONE"
                                                    soundResId = 0
                                                    statusMsg = "Status: Studying 📖"
                                                }
                                            }

                                            // Evaluate State Timers (3 Second Buffer)
                                            if (currentState == "NONE") {
                                                activeDistractionState = "NONE"
                                                distractionStartTime = 0L
                                                runOnUiThread { tvEyeRatio.text = statusMsg }
                                            } else {
                                                if (activeDistractionState != currentState) {
                                                    activeDistractionState = currentState
                                                    distractionStartTime = currentTime
                                                } else {
                                                    // State continuous duration check
                                                    val elapsedTime = currentTime - distractionStartTime
                                                    if (elapsedTime >= DISTRACTION_HOLD_TIME_MS) {
                                                        triggerVoice(statusMsg, soundResId, currentTime)
                                                    } else {
                                                        runOnUiThread {
                                                            val remainingSec = ((DISTRACTION_HOLD_TIME_MS - elapsedTime) / 1000) + 1
                                                            tvEyeRatio.text = "$statusMsg\nWarning in: ${remainingSec}s"
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
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                // Exception handling
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun triggerVoice(statusText: String, soundResId: Int, currentTime: Long) {
        runOnUiThread {
            tvEyeRatio.text = statusText
        }
        if (currentTime - lastVoiceTime >= VOICE_COOLDOWN_MS) {
            playVoice(soundResId)
            lastVoiceTime = currentTime
        }
    }

    private fun playVoice(soundResId: Int) {
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

