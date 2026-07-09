package com.billscanner.data.camera

import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

object ImagePreprocessor {

    private const val TAG = "ImagePreprocessor"

    fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = yuv420ToBitmap(imageProxy)
            ?: throw IllegalStateException("Failed to convert image to bitmap")
        return enhanceForOcr(bitmap)
    }

    private fun yuv420ToBitmap(imageProxy: ImageProxy): Bitmap? {
        try {
            val nv21 = yuv420ToNv21(imageProxy) ?: return null
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
            val jpegBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "YUV conversion failed", e)
            return null
        }
    }

    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray? {
        try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            return nv21
        } catch (e: Exception) {
            Log.e(TAG, "NV21 conversion failed", e)
            return null
        }
    }

    private fun enhanceForOcr(original: Bitmap): Bitmap {
        val width = original.width
        val height = original.height
        val config = original.config ?: Bitmap.Config.ARGB_8888

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
