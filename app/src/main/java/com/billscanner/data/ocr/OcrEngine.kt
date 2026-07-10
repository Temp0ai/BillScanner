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

    private val recognizer: TextRecognizer

    init {
        Log.d(TAG, "Creating ChineseTextRecognizer...")
        recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Log.d(TAG, "ChineseTextRecognizer created OK")
    }

    suspend fun processBitmap(bitmap: Bitmap): Result<Text> {
        return try {
            Log.d(TAG, "Processing bitmap ${bitmap.width}x${bitmap.height}...")
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(inputImage).await()
            Log.d(TAG, "OCR done: ${result.textBlocks.size} blocks")
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
