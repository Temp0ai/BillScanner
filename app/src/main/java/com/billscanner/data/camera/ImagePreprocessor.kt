package com.billscanner.data.camera

import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

object ImagePreprocessor {

    private const val TAG = "ImagePreprocessor"

    fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer: ByteBuffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Failed to decode image from buffer")

        return enhanceForOcr(bitmap)
    }

    private fun enhanceForOcr(original: Bitmap): Bitmap {
        val width = original.width
        val height = original.height
        val config = original.config ?: Bitmap.Config.ARGB_8888

        // Increase contrast
        val enhanced = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(enhanced)
        val paint = Paint().apply {
            isAntiAlias = true
        }

        val contrast = 1.4f
        val brightness = -20f
        val colorMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(original, 0f, 0f, paint)

        if (original !== enhanced) {
            original.recycle()
        }

        // Convert to grayscale
        val grayscale = Bitmap.createBitmap(width, height, config)
        val gCanvas = Canvas(grayscale)
        val gPaint = Paint().apply {
            isAntiAlias = true
        }
        val grayMatrix = ColorMatrix().apply {
            setSaturation(0f)
        }
        gPaint.colorFilter = ColorMatrixColorFilter(grayMatrix)
        gCanvas.drawBitmap(enhanced, 0f, 0f, gPaint)

        enhanced.recycle()
        return grayscale
    }
}
