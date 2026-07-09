package com.billscanner.data.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrEngine {

    companion object {
        private const val TAG = "OcrEngine"
    }

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun processBitmap(bitmap: Bitmap): Result<Text> {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(inputImage).await()
            Log.d(TAG, "OCR processed: ${result.textBlocks.size} blocks found")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "OCR processing failed", e)
            Result.failure(e)
        }
    }

    fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing recognizer", e)
        }
    }
}
