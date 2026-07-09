package com.billscanner.domain.usecase

import android.graphics.Bitmap
import android.util.Log
import com.billscanner.data.camera.ImagePreprocessor
import com.billscanner.data.ocr.OcrEngine
import com.billscanner.data.storage.CsvRepository
import com.billscanner.domain.parser.ExtractedCustomer
import com.billscanner.domain.parser.OcrParser
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class CaptureState {
    object Idle : CaptureState()
    data class Processing(val frameCount: Int) : CaptureState()
    data class Captured(
        val customers: List<ExtractedCustomer>,
        val writtenCount: Int,
        val frameCount: Int
    ) : CaptureState()
    data class Error(val message: String, val frameCount: Int) : CaptureState()
    object Paused : CaptureState()
}

class CaptureUseCase(
    private val ocrEngine: OcrEngine,
    private val csvRepository: CsvRepository,
    private val parser: OcrParser = OcrParser()
) {
    companion object {
        private const val TAG = "CaptureUseCase"
        private const val FRAME_INTERVAL_MS = 1000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var frameCount = 0
    private var isPaused = false
    private var lastProcessedAt = 0L

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state

    fun onFrame(imageProxy: ImageProxy) {
        if (isPaused) {
            imageProxy.close()
            return
        }

        // Frame throttling: skip if processed recently
        val now = System.currentTimeMillis()
        if (now - lastProcessedAt < FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastProcessedAt = now

        scope.launch {
            try {
                _state.value = CaptureState.Processing(frameCount)

                // 1. Convert ImageProxy to preprocessed Bitmap
                val bitmap: Bitmap = withContext(Dispatchers.Default) {
                    ImagePreprocessor.toBitmap(imageProxy)
                }

                // 2. Run OCR
                val ocrResult = ocrEngine.processBitmap(bitmap)
                bitmap.recycle()

                ocrResult.fold(
                    onSuccess = { text ->
                        // 3. Parse structured data
                        val customers = parser.parse(text)

                        if (customers.isNotEmpty()) {
                            // 4. Append to CSV
                            val written = withContext(Dispatchers.IO) {
                                csvRepository.append(customers)
                            }
                            frameCount++
                            Log.d(TAG, "Captured ${customers.size} customers, $written new written")
                            _state.value = CaptureState.Captured(customers, written, frameCount)
                        } else {
                            _state.value = CaptureState.Processing(frameCount)
                        }
                    },
                    onFailure = { e ->
                        Log.e(TAG, "OCR failed", e)
                        _state.value = CaptureState.Error("OCR failed: ${e.message}", frameCount)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                _state.value = CaptureState.Error("Unexpected: ${e.message}", frameCount)
            } finally {
                imageProxy.close()
            }
        }
    }

    fun pause() {
        isPaused = true
        _state.value = CaptureState.Paused
    }

    fun resume() {
        isPaused = false
        lastProcessedAt = 0L
        _state.value = CaptureState.Idle
    }

    fun reset() {
        pause()
        frameCount = 0
        _state.value = CaptureState.Idle
    }

    fun destroy() {
        scope.cancel()
        ocrEngine.close()
    }
}
