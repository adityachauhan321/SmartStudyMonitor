package com.example.smartstudymonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.objects.DetectedObject

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val faces = mutableListOf<Face>()
    private val objects = mutableListOf<DetectedObject>()
    private var imageWidth = 480
    private var imageHeight = 640

    private val meshPaint = Paint().apply {
        color = Color.parseColor("#00FFCC")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val phonePaint = Paint().apply {
        color = Color.parseColor("#FF007F")
        style = Paint.Style.STROKE
        strokeWidth = 7f
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#FF007F")
        textSize = 45f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setResults(detectedFaces: List<Face>, detectedObjects: List<DetectedObject>, width: Int, height: Int) {
        faces.clear()
        faces.addAll(detectedFaces)
        objects.clear()
        objects.addAll(detectedObjects)
        imageWidth = width
        imageHeight = height
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scaleX = width.toFloat() / imageHeight.toFloat()
        val scaleY = height.toFloat() / imageWidth.toFloat()

        // 1. Draw Virtual Face Mesh Mesh Lines
        for (face in faces) {
            val bounds = face.boundingBox
            val rectF = RectF(
                width - (bounds.right * scaleX),
                bounds.top * scaleY,
                width - (bounds.left * scaleX),
                bounds.bottom * scaleY
            )
            canvas.drawRect(rectF, meshPaint)

            for (landmark in face.allLandmarks) {
                val cx = width - (landmark.position.x * scaleX)
                val cy = landmark.position.y * scaleY
                canvas.drawCircle(cx, cy, 6f, meshPaint)
            }
        }

        // 2. Draw Virtual Phone Bounding Box & Tag
        for (obj in objects) {
            val bounds = obj.boundingBox
            val rectF = RectF(
                width - (bounds.right * scaleX),
                bounds.top * scaleY,
                width - (bounds.left * scaleX),
                bounds.bottom * scaleY
            )
            canvas.drawRect(rectF, phonePaint)
            canvas.drawText("Phone Detected", rectF.left, rectF.top - 15f, textPaint)
        }
    }
}
