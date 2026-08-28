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
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvEyeRatio: TextView
    private lateinit var cameraExecutor: ExecutorService
    
    private var mediaPlayer: MediaPlayer? = null

    private var distractionStartTime: Long = 0L
    private val DISTRACTION_THRESHOLD_MS = 3000L
    private var lastAudioPlayTime: Long = 0L
    private val AUDIO_COOLDOWN_MS = 4000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        tvEyeRatio = findViewById(R.id.tvEyeRatio)

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

            val preview = Preview.Builder()
                .build()
                .also {
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
                                    var phoneDetected = false
                                    for (obj in objects) {
                                        for (label in obj.labels) {
                                            if (label.text.contains("Mobile phone", ignoreCase = true) || 
                                                label.text.contains("Cell phone", ignoreCase = true) ||
                                                label.text.contains("Phone", ignoreCase = true)) {
                                                phoneDetected = true
                                                break
                                            }
                                        }
                                    }

                                    if (phoneDetected) {
                                        triggerDistraction("PUT THE PHONE AWAY!", R.raw.put_phone, currentTime)
                                        imageProxy.close()
                                        return@addOnSuccessListener
                                    }

                                    faceDetector.process(image)
                                        .addOnSuccessListener { faces ->
                                            if (faces.isNotEmpty()) {
                                                val face = faces[0]
                                                val leftOpen = face.leftEyeOpenProbability ?: -1f
                                                val rightOpen = face.rightEyeOpenProbability ?: -1f
                                                val rotY = face.headEulerAngleY

                                                if (leftOpen != -1f && rightOpen != -1f) {
                                                    val avgRatio = (leftOpen + rightOpen) / 2.0f
                                                    val ratioPercentage = (avgRatio * 100).toInt()

                                                    if (Math.abs(rotY) > 40) {
                                                        triggerDistraction("DON'T COVER YOUR FACE!", R.raw.cover_face, currentTime)
                                                    } 
                                                    else if (avgRatio < 0.2f) {
                                                        triggerDistraction("WAKE UP & STUDY!", R.raw.wake_up, currentTime)
                                                    } 
                                                    else {
                                                        distractionStartTime = 0L
                                                        runOnUiThread {
                                                            tvEyeRatio.text = "Status: Studying 📖\nEye Ratio: $ratioPercentage%"
                                                        }
                                                    }
                                                }
                                            } else {
                                                triggerDistraction("DON'T COVER YOUR FACE!", R.raw.cover_face, currentTime)
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

    private fun triggerDistraction(statusMsg: String, rawSoundId: Int, currentTime: Long) {
        runOnUiThread {
            tvEyeRatio.text = statusMsg
        }

        if (distractionStartTime == 0L) {
            distractionStartTime = currentTime
        } else if (currentTime - distractionStartTime >= DISTRACTION_THRESHOLD_MS) {
            if (currentTime - lastAudioPlayTime >= AUDIO_COOLDOWN_MS) {
                playSpecificSound(rawSoundId)
                lastAudioPlayTime = currentTime
            }
        }
    }

    private fun playSpecificSound(soundResId: Int) {
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
