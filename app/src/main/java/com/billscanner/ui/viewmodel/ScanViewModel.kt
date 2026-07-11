package com.billscanner.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.billscanner.CrashLogger
import com.billscanner.data.ocr.OcrEngine
import com.billscanner.data.storage.CsvRepository
import com.billscanner.domain.usecase.CaptureUseCase
import com.billscanner.domain.usecase.CaptureState
import com.billscanner.domain.parser.ExtractedCustomer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val csvRepo = CsvRepository(application)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var _useCase: CaptureUseCase? = null
    private var ocrFailed = false

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    val sessionResults: List<ExtractedCustomer>
        get() = csvRepo.readCurrentSession()

    fun exportCsvPath(): String? = csvRepo.getCurrentFilePath()

    fun getShareIntent() = csvRepo.getShareIntent()

    fun initCamera() {
        if (_useCase != null || ocrFailed) return
        try {
            val engine = OcrEngine()
            val uc = CaptureUseCase(engine, csvRepo)
            _useCase = uc
            scope.launch {
                uc.state.collect { _captureState.value = it }
            }
            Log.d("ScanViewModel", "OcrEngine initialized OK")
        } catch (e: Exception) {
            Log.e("ScanViewModel", "Failed to init OcrEngine", e)
            CrashLogger.logError(getApplication(), "ScanViewModel.initCamera", e)
            ocrFailed = true
            _captureState.value = CaptureState.Error("OCR init failed: ${e.message}", 0)
        }
    }

    fun getCaptureUseCase(): CaptureUseCase? = _useCase
    fun pauseScan() { _useCase?.pause() }
    fun resumeScan() { initCamera(); _useCase?.resume() }
    fun resetScan() {
        _useCase?.reset()
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
        _useCase?.destroy()
    }
}
