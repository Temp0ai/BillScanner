package com.billscanner.data.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var isBound = false

    fun startCamera(
        previewView: PreviewView,
        onFrameAvailable: (ImageProxy) -> Unit,
        onError: (String) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(previewView, onFrameAvailable, onError)
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider initialization failed", e)
                onError("Camera initialization failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(
        previewView: PreviewView,
        onFrameAvailable: (ImageProxy) -> Unit,
        onError: (String) -> Unit
    ) {
        val provider = cameraProvider ?: run {
            onError("Camera provider not available")
            return
        }

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    onFrameAvailable(imageProxy)
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, imageAnalysis
            )
            isBound = true
            Log.d(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            onError("Camera binding failed: ${e.message}")
        }
    }

    fun stopCamera() {
        if (isBound) {
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera", e)
            }
            isBound = false
        }
        if (!analysisExecutor.isShutdown) {
            analysisExecutor.shutdown()
        }
    }
}
