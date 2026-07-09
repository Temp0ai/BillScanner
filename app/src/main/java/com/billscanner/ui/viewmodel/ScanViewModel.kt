package com.billscanner.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.billscanner.data.ocr.OcrEngine
import com.billscanner.data.storage.CsvRepository
import com.billscanner.domain.usecase.CaptureUseCase
import com.billscanner.domain.usecase.CaptureState
import com.billscanner.domain.parser.ExtractedCustomer
import kotlinx.coroutines.flow.StateFlow

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val csvRepo = CsvRepository(application)
    private val ocrEngine = OcrEngine()

    val captureUseCase = CaptureUseCase(ocrEngine, csvRepo)

    val captureState: StateFlow<CaptureState> = captureUseCase.state

    val sessionResults: List<ExtractedCustomer>
        get() = csvRepo.readCurrentSession()

    fun exportCsvPath(): String? = csvRepo.getCurrentFilePath()

    fun pauseScan() = captureUseCase.pause()
    fun resumeScan() = captureUseCase.resume()
    fun resetScan() = captureUseCase.reset()

    override fun onCleared() {
        super.onCleared()
        captureUseCase.destroy()
    }
}
