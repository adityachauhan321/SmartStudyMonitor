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
    
    private val facePaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    
    private val boxPaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textPaint = Paint().apply {
        color = Color.MAGENTA
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setResults(detectedFaces: List<Face>, detectedObjects: List<DetectedObject>) {
        faces.clear()
        faces.addAll(detectedFaces)
        objects.clear()
        objects.addAll(detectedObjects)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Mesh Contours / Landmarks
        for (face in faces) {
            for (landmark in face.allLandmarks) {
                canvas.drawCircle(landmark.position.x, landmark.position.y, 4f, facePaint)
            }
        }

        // Draw Object (Phone) Detection Box & Label
        for (obj in objects) {
            val bounds = obj.boundingBox
            canvas.drawRect(bounds, boxPaint)
            canvas.drawText("Phone Detected", bounds.left.toFloat(), bounds.top.toFloat() - 10, textPaint)
        }
    }
}
