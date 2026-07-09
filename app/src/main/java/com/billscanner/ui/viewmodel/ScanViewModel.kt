package com.billscanner.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.billscanner.data.ocr.OcrEngine
import com.billscanner.data.storage.CsvRepository
import com.billscanner.domain.usecase.CaptureUseCase
import com.billscanner.domain.usecase.CaptureState
import com.billscanner.domain.parser.ExtractedCustomer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val csvRepo = CsvRepository(application)

    private var _useCase: CaptureUseCase? = null

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    val sessionResults: List<ExtractedCustomer>
        get() = csvRepo.readCurrentSession()

    fun exportCsvPath(): String? = csvRepo.getCurrentFilePath()

    fun initCamera() {
        if (_useCase == null) {
            val engine = OcrEngine()
            val uc = CaptureUseCase(engine, csvRepo)
            _useCase = uc
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                uc.state.collect { _captureState.value = it }
            }
        }
    }

    fun getCaptureUseCase(): CaptureUseCase? = _useCase
    fun pauseScan() { _useCase?.pause() }
    fun resumeScan() { initCamera(); _useCase?.resume() }
    fun resetScan() { initCamera(); _useCase?.reset() }

    override fun onCleared() {
        super.onCleared()
        _useCase?.destroy()
    }
}
