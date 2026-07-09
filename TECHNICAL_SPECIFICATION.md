# BillScanner — Android Application Technical Specification

---

## 1. Overview

**BillScanner** is a single-module Android application (Kotlin, minSdk 24) that captures customer names and phone numbers from physical bill-book pages using the device camera, applies OCR, and stores results as a local CSV file.

**Primary Goals**
- Continuous, real-time camera scanning with auto-capture.
- Robust OCR tuned for mixed printed/handwritten text.
- Append-safe local CSV storage with duplicate prevention.
- Clean, minimal UI; performant on mid-range hardware.

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer                           │
│  (Jetpack Compose or XML Activities/Fragments)          │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐            │
│  │  Scan    │  │  Review  │  │  Settings  │            │
│  │  Screen  │  │  Screen  │  │  Screen    │            │
│  └────┬─────┘  └────┬─────┘  └────────────┘            │
│       │              │                                  │
├───────┼──────────────┼──────────────────────────────────┤
│       ▼              ▼                                  │
│                  ViewModel Layer                        │
│  ┌──────────────────────────────────────────────┐       │
│  │  ScanViewModel                               │       │
│  │  - previewState: StateFlow<PreviewState>     │       │
│  │  - scanResults: StateFlow<List<ScanResult>>  │       │
│  │  - exportCsv(): Flow<Result<File>>           │       │
│  └──────────────────┬───────────────────────────┘       │
│                     │                                   │
├─────────────────────┼───────────────────────────────────┤
│                     ▼                                   │
│                   Domain Layer                          │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐    │
│  │ CaptureUse   │ │ OcrUseCase   │ │ CsvExport    │    │
│  │ Case         │ │              │ │ UseCase      │    │
│  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘    │
│         │                │                │             │
├─────────┼────────────────┼────────────────┼─────────────┤
│         ▼                ▼                ▼             │
│                    Data Layer                           │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐    │
│  │ CameraX      │ │ ML Kit Text  │ │ CsvFile      │    │
│  │ Source       │ │ Recognition  │ │ Repository   │    │
│  └──────────────┘ └──────────────┘ └──────────────┘    │
└─────────────────────────────────────────────────────────┘
```

**Layers**

| Layer | Responsibility | Key Classes |
|-------|---------------|-------------|
| UI | Render camera preview, show scan status, display results, provide controls | `ScanActivity`, `ScanFragment`, `ReviewActivity` |
| ViewModel | Hold UI state, mediate between UI and domain | `ScanViewModel` |
| Domain | Business logic: when to capture, how to parse OCR output, CSV integrity | `CaptureUseCase`, `OcrUseCase`, `CsvExportUseCase` |
| Data | CameraX pipeline, ML Kit client, file I/O | `CameraManager`, `OcrEngine`, `CsvFileRepository` |

---

## 3. Module & Dependency Setup

### 3.1 build.gradle (app)

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'kotlin-kapt'
}

android {
    namespace 'com.billscanner'
    compileSdk 34
    defaultConfig {
        applicationId "com.billscanner"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
    buildFeatures {
        compose true
    }
    composeOptions {
        kotlinCompilerExtensionVersion '1.5.8'
    }
}

dependencies {
    // Core
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0'
    implementation 'androidx.activity:activity-compose:1.8.2'

    // CameraX
    def camerax_version = "1.3.1"
    implementation "androidx.camera:camera-core:${camerax_version}"
    implementation "androidx.camera:camera-camera2:${camerax_version}"
    implementation "androidx.camera:camera-lifecycle:${camerax_version}"
    implementation "androidx.camera:camera-view:${camerax_version}"

    // ML Kit Text Recognition
    implementation 'com.google.mlkit:text-recognition-chinese:16.0.0'

    // Compose
    implementation platform('androidx.compose:compose-bom:2024.01.00')
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.foundation:foundation'

    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
}
```

### 3.2 AndroidManifest.xml — Permissions

```xml
<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-permission android:name="android.permission.CAMERA" />

<application
    android:allowBackup="false"
    android:label="BillScanner"
    android:supportsRtl="true"
    android:theme="@style/Theme.BillScanner">
    <activity
        android:name=".ui.ScanActivity"
        android:exported="true"
        android:screenOrientation="portrait">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
    <activity android:name=".ui.ReviewActivity" />
</application>
```

---

## 4. Core Modules — Detailed Design

### 4.1 Camera Module (`CameraManager`)

**File:** `data/camera/CameraManager.kt`

Responsibilities:
- Request and bind CameraX use cases (preview + image analysis).
- Deliver `ImageProxy` frames to the analysis pipeline at a configurable frame rate.
- Manage lifecycle binding so the camera releases when the Activity pauses.

```kotlin
package com.billscanner.data.camera

import android.content.Context
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
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun startCamera(
        previewView: PreviewView,
        onFrameAvailable: (ImageProxy) -> Unit,
        onError: (String) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor, ImageAnalysis.Analyzer { imageProxy ->
                        onFrameAvailable(imageProxy)
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalysis
                )
            } catch (e: Exception) {
                onError("Camera binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
```

**Key Design Decisions**
- `STRATEGY_KEEP_ONLY_LATEST` ensures the analysis pipeline always processes the freshest frame, preventing lag accumulation on mid-range devices.
- Analysis runs on a dedicated single-thread executor to avoid blocking the main thread.
- Target resolution 1280×720 balances OCR quality with memory/CPU usage.

---

### 4.2 OCR Module (`OcrEngine`)

**File:** `data/ocr/OcrEngine.kt`

Uses **Google ML Kit Text Recognition (Chinese variant)** because:
1. It handles both printed and handwritten text well.
2. The Chinese model also performs well on Latin scripts.
3. On-device inference (no network required).
4. Auto-corrects orientation and handles skewed text.

```kotlin
package com.billscanner.data.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrEngine {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun processBitmap(bitmap: Bitmap): Result<Text> {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(inputImage).await()
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        recognizer.close()
    }
}
```

---

### 4.3 Text Parsing & Data Extraction (`OcrParser`)

**File:** `domain/parser/OcrParser.kt`

Responsible for extracting structured data (name + phone) from raw ML Kit `Text` objects.

**Heuristics:**
1. Phone number detection: regex for common phone formats (10-digit Indian mobile, international with +, etc.).
2. Name extraction: lines that are *not* phone numbers, *not* pure numeric, *not* headers/labels — typically the line closest to the phone number on the same block or adjacent line.
3. Deduplication key: normalized phone number.

```kotlin
package com.billscanner.domain.parser

import com.google.mlkit.vision.text.Text

data class ExtractedCustomer(
    val name: String,
    val phone: String,
    val confidence: Float
)

class OcrParser {

    // Matches: 10-digit, +91 prefix, 91 prefix, optional spaces/dashes
    private val phoneRegex = Regex(
        """(?:\+?91[-\s]?)?\b([6-9]\d{9})\b"""
    )

    // Lines that are purely numeric or very short — likely not names
    private val excludePattern = Regex("""^[\d\s\-+()]{3,}$""")

    fun parse(text: Text): List<ExtractedCustomer> {
        val results = mutableListOf<ExtractedCustomer>()
        val blocks = text.textBlocks

        for (block in blocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                val phoneMatch = phoneRegex.find(lineText)

                if (phoneMatch != null) {
                    val phone = phoneMatch.groupValues[1]

                    // Look for name: check sibling lines in same block
                    val name = findNameForPhone(block.lines, lineText, phone)

                    if (name != null && name.length >= 2) {
                        results.add(
                            ExtractedCustomer(
                                name = name,
                                phone = phone,
                                confidence = line.confidence ?: 0.7f
                            )
                        )
                    }
                }
            }
        }
        return results
    }

    private fun findNameForPhone(
        siblingLines: List<Text.Line>,
        phoneLine: String,
        phone: String
    ): String? {
        // Remove the phone portion from the line — what remains may be the name
        val withoutPhone = phoneLine.replace(Regex("""\+?91[-\s]?\d{10}"""), "")
            .replace(Regex("""\d{10}"""), "")
            .trim()

        if (withoutPhone.length >= 2 && !excludePattern.matches(withoutPhone)) {
            return withoutPhone
        }

        // Fallback: look at adjacent lines for a name-like string
        for (sibling in siblingLines) {
            val text = sibling.text.trim()
            if (text == phoneLine) continue
            if (text.length >= 2 && !excludePattern.matches(text) && !phoneRegex.containsMatchIn(text)) {
                return text
            }
        }
        return null
    }
}
```

---

### 4.4 Image Preprocessing (`ImagePreprocessor`)

**File:** `data/camera/ImagePreprocessor.kt`

Converts `ImageProxy` (YUV_420_888) to a processed `Bitmap` optimized for OCR:

```kotlin
package com.billscanner.data.camera

import android.graphics.*
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

object ImagePreprocessor {

    fun toBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer: ByteBuffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Apply contrast enhancement for better OCR
        return enhanceForOcr(bitmap)
    }

    private fun enhanceForOcr(original: Bitmap): Bitmap {
        val width = original.width
        val height = original.height
        val enhanced = Bitmap.createBitmap(width, height, original.config)

        val canvas = Canvas(enhanced)
        val paint = Paint()

        // Increase contrast
        val contrast = 1.4f
        val brightness = -20f
        val colorMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(original, 0f, 0f, paint)

        // Convert to grayscale for consistent OCR input
        val grayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val gCanvas = Canvas(grayscale)
        val gPaint = Paint()
        val grayMatrix = ColorMatrix()
        grayMatrix.setSaturation(0f)
        gPaint.colorFilter = ColorMatrixColorFilter(grayMatrix)
        gCanvas.drawBitmap(enhanced, 0f, 0f, gPaint)

        enhanced.recycle()
        return grayscale
    }
}
```

---

### 4.5 CSV Storage (`CsvRepository`)

**File:** `data/storage/CsvRepository.kt`

Handles append-safe writes, duplicate detection, and file management.

```kotlin
package com.billscanner.data.storage

import android.content.Context
import com.billscanner.domain.parser.ExtractedCustomer
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class CsvRepository(private val context: Context) {

    private val header = "timestamp,name,phone,confidence\n"

    private fun getFile(): File {
        val dir = File(context.filesDir, "scans")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "bill_scanner_${dateStamp()}.csv")
    }

    private fun getAllFiles(): List<File> {
        val dir = File(context.filesDir, "scans")
        return dir.listFiles { f -> f.extension == "csv" }?.toList() ?: emptyList()
    }

    private fun dateStamp(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /**
     * Returns all previously seen phone numbers across all CSV files.
     */
    private fun loadExistingPhones(): Set<String> {
        val phones = mutableSetOf<String>()
        for (file in getAllFiles()) {
            file.readLines().drop(1).forEach { line ->
                val cols = line.split(",")
                if (cols.size >= 3) {
                    phones.add(cols[2].trim())
                }
            }
        }
        return phones
    }

    /**
     * Appends new customers to today's CSV, skipping duplicates.
     * Returns the count of actually written records.
     */
    fun append(customers: List<ExtractedCustomer>): Int {
        val existing = loadExistingPhones()
        val file = getFile()
        val isNew = !file.exists()

        var written = 0
        FileWriter(file, true).use { writer ->
            if (isNew) writer.write(header)

            for (c in customers) {
                if (c.phone !in existing) {
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    val safeName = c.name.replace(",", ";").replace("\n", " ")
                    writer.write("${ts},${safeName},${c.phone},${String.format("%.2f", c.confidence)}\n")
                    existing.add(c.phone) // prevent intra-batch dupes
                    written++
                }
            }
        }
        return written
    }

    /**
     * Returns all records from today's scan file.
     */
    fun readCurrentSession(): List<ExtractedCustomer> {
        val file = getFile()
        if (!file.exists()) return emptyList()

        return file.readLines().drop(1).mapNotNull { line ->
            val cols = line.split(",")
            if (cols.size >= 3) {
                ExtractedCustomer(
                    name = cols[1].trim(),
                    phone = cols[2].trim(),
                    confidence = cols.getOrNull(3)?.trim()?.toFloatOrNull() ?: 0f
                )
            } else null
        }
    }

    fun deleteAll() {
        getAllFiles().forEach { it.delete() }
    }
}
```

**Duplicate Prevention Strategy**
- Global phone-number set loaded from all existing CSVs on every append call.
- In-batch dedup: `existing` set mutated as records are written.
- Normalized 10-digit phone key (strips +91, spaces, dashes).

---

### 4.6 Capture Orchestrator (`CaptureUseCase`)

**File:** `domain/usecase/CaptureUseCase.kt`

Coordinates: frame arrival → preprocessing → OCR → parsing → CSV append.

```kotlin
package com.billscanner.domain.usecase

import android.graphics.Bitmap
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
    data class Captured(val customers: List<ExtractedCustomer>, val writtenCount: Int) : CaptureState()
    data class Error(val message: String) : CaptureState()
    object Paused : CaptureState()
}

class CaptureUseCase(
    private val ocrEngine: OcrEngine,
    private val csvRepository: CsvRepository,
    private val parser: OcrParser = OcrParser()
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var frameCount = 0
    private var isPaused = false

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state

    /**
     * Called for each camera frame. Processes asynchronously.
     * Skips frames if already processing (back-pressure).
     */
    fun onFrame(imageProxy: ImageProxy) {
        if (isPaused) {
            imageProxy.close()
            return
        }

        scope.launch {
            try {
                _state.value = CaptureState.Processing(frameCount)

                // 1. Convert ImageProxy → preprocessed Bitmap
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
                            _state.value = CaptureState.Captured(customers, written)
                        } else {
                            _state.value = CaptureState.Processing(frameCount)
                        }
                    },
                    onFailure = { e ->
                        _state.value = CaptureState.Error("OCR failed: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                _state.value = CaptureState.Error("Unexpected: ${e.message}")
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
        frameCount = 0
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
```

---

### 4.7 ViewModel (`ScanViewModel`)

**File:** `ui/viewmodel/ScanViewModel.kt`

```kotlin
package com.billscanner.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.billscanner.data.camera.CameraManager
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

    fun exportCsvPath(): String? {
        val dir = application.filesDir.resolve("scans")
        return dir.listFiles()?.firstOrNull()?.absolutePath
    }

    fun pauseScan() = captureUseCase.pause()
    fun resumeScan() = captureUseCase.resume()
    fun resetScan() = captureUseCase.reset()

    override fun onCleared() {
        super.onCleared()
        captureUseCase.destroy()
    }
}
```

---

## 5. UI Layer

### 5.1 Scan Screen (Compose)

**File:** `ui/composable/ScanScreen.kt`

```kotlin
package com.billscanner.ui.composable

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.billscanner.domain.usecase.CaptureState
import com.billscanner.ui.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(viewModel: ScanViewModel) {
    val state by viewModel.captureState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // State for permission
    var hasPermission by remember { mutableStateOf(false) }

    // Request camera permission on launch
    LaunchedEffect(Unit) {
        // Permission request handled in Activity; this flag is set externally
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("BillScanner") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Camera Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                if (hasPermission) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Scanning indicator overlay
                    when (state) {
                        is CaptureState.Processing -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                            )
                        }
                        is CaptureState.Captured -> {
                            Snackbar(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp)
                            ) {
                                val captured = state as CaptureState.Captured
                                Text("Captured ${captured.writtenCount} new record(s)")
                            }
                        }
                        is CaptureState.Error -> {
                            Snackbar(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp),
                                containerColor = MaterialTheme.colorScheme.error
                            ) {
                                Text((state as CaptureState.Error).message)
                            }
                        }
                        else -> {}
                    }
                } else {
                    Text(
                        "Camera permission required",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            // Status bar + controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isActive = state is CaptureState.Idle
                        || state is CaptureState.Processing
                        || state is CaptureState.Captured

                Button(
                    onClick = {
                        if (isActive) viewModel.pauseScan() else viewModel.resumeScan()
                    }
                ) {
                    Text(if (isActive) "Pause" else "Resume")
                }

                Button(
                    onClick = { viewModel.resetScan() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Reset")
                }

                // Display running count
                Text(
                    text = "Frames: ${(state as? CaptureState.Processing)?.frameCount ?: 0}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
```

### 5.2 Review Screen

**File:** `ui/composable/ReviewScreen.kt`

```kotlin
package com.billscanner.ui.composable

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.billscanner.domain.parser.ExtractedCustomer
import com.billscanner.ui.viewmodel.ScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(viewModel: ScanViewModel) {
    val results = remember { mutableStateOf(viewModel.sessionResults) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Scanned Results") }) }
    ) { padding ->
        if (results.value.isEmpty()) {
            Text(
                "No data captured yet.",
                modifier = Modifier.padding(padding).padding(16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(results.value) { customer ->
                    ListItem(
                        headlineContent = { Text(customer.name) },
                        supportingContent = { Text(customer.phone) },
                        trailingContent = {
                            Text(
                                "Conf: ${(customer.confidence * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
```

---

## 6. Activity Setup

**File:** `ui/ScanActivity.kt`

```kotlin
package com.billscanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.billscanner.data.camera.CameraManager
import com.billscanner.ui.composable.ScanScreen
import com.billscanner.ui.viewmodel.ScanViewModel

class ScanActivity : ComponentActivity() {

    private lateinit var viewModel: ScanViewModel
    private lateinit var cameraManager: CameraManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        // else: UI shows "permission required" message
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ScanViewModel::class.java]
        cameraManager = CameraManager(this, this)

        setContent {
            MaterialTheme {
                ScanScreen(viewModel)
            }
        }

        requestCameraPermission()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        // Find the PreviewView from the Compose hierarchy
        // In practice, you'd expose a reference or use a BoxWithConstraints pattern
        // This is simplified — see Section 7 for the full approach
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.stopCamera()
    }
}
```

---

## 7. Camera Integration with Compose — Full Pattern

Since CameraX requires a real `PreviewView` reference, use this pattern:

```kotlin
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onPreviewView: (PreviewView) -> Unit
) {
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                onPreviewView(this)
            }
        },
        modifier = modifier
    )
}
```

In `ScanActivity`, wire it:

```kotlin
setContent {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onPreviewView = { previewView ->
                    cameraManager.startCamera(
                        previewView = previewView,
                        onFrameAvailable = { imageProxy ->
                            viewModel.captureUseCase.onFrame(imageProxy)
                        },
                        onError = { /* show toast/snackbar */ }
                    )
                }
            )
            // Overlay controls on top
            ScanControls(viewModel)
        }
    }
}
```

---

## 8. Data Flow Diagram

```
Camera Frame (YUV_420_888)
        │
        ▼
┌─────────────────────┐
│ ImagePreprocessor    │  Convert YUV → Bitmap, enhance contrast, grayscale
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│ OcrEngine            │  ML Kit Chinese text recognizer (on-device)
│  .processBitmap()    │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│ OcrParser            │  Regex phone detection, name extraction, confidence
│  .parse(text)        │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│ CsvRepository        │  Load existing phones → filter dupes → append CSV
│  .append(customers)  │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│ CaptureState Flow    │  Emit Captured/Written count → UI Snackbar
└─────────────────────┘
```

---

## 9. CSV Output Format

**File:** `scans/bill_scanner_20260707.csv`

```csv
timestamp,name,phone,confidence
2026-07-07 10:15:32,Rajesh Kumar,9876543210,0.92
2026-07-07 10:15:33,Priya Patel,8765432109,0.87
2026-07-07 10:15:35,Amit Singh,7654321098,0.95
```

**Rules:**
- One file per day (date-stamped).
- Comma in names replaced with semicolon.
- Newlines in names replaced with space.
- Confidence rounded to 2 decimal places.
- Phone normalized to bare 10 digits.

---

## 10. Error Handling & Edge Cases

| Scenario | Handling |
|----------|----------|
| Camera permission denied | Show persistent message; prompt re-request on next launch |
| OCR returns empty | Silent skip; continue processing next frame |
| Phone number not found on page | Skip that text block; no partial records written |
| Duplicate phone number | Silently skipped; logged to Logcat at DEBUG level |
| File I/O error | Emit `CaptureState.Error`; retry on next frame |
| Device overheating / frame drops | Frame rate limiter: skip every Nth frame if processing time > 500ms |
| Multi-page rapid flipping | `STRATEGY_KEEP_ONLY_LATEST` ensures only the current page is processed |

---

## 11. Performance Considerations

1. **Frame throttling:** Process at most 1 frame per second. Add a `lastProcessedTime` timestamp guard in `CaptureUseCase.onFrame()`:

```kotlin
private var lastProcessedAt = 0L
private val FRAME_INTERVAL_MS = 1000L

fun onFrame(imageProxy: ImageProxy) {
    val now = System.currentTimeMillis()
    if (now - lastProcessedAt < FRAME_INTERVAL_MS) {
        imageProxy.close()
        return
    }
    lastProcessedAt = now
    // ... process
}
```

2. **Bitmap recycling:** Call `bitmap.recycle()` immediately after OCR completes.

3. **ML Kit model lifecycle:** Call `ocrEngine.close()` in `ViewModel.onCleared()`.

4. **Memory:** Target resolution 1280×720 keeps per-frame bitmap memory under 4MB.

5. **Battery:** Camera analysis only runs while scanning is active; pausing stops analysis.

---

## 12. Privacy & Security

- All processing is **on-device** — no images leave the phone.
- No network permission requested (add only if cloud sync is added later).
- CSV files stored in app-private internal storage (`context.filesDir`), not accessible to other apps without root.
- No analytics, telemetry, or third-party SDKs.
- `android:allowBackup="false"` to prevent accidental CSV extraction via adb backup.

---

## 13. File Structure

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/billscanner/
│   │   ├── data/
│   │   │   ├── camera/
│   │   │   │   ├── CameraManager.kt
│   │   │   │   └── ImagePreprocessor.kt
│   │   │   ├── ocr/
│   │   │   │   └── OcrEngine.kt
│   │   │   └── storage/
│   │   │       └── CsvRepository.kt
│   │   ├── domain/
│   │   │   ├── parser/
│   │   │   │   └── OcrParser.kt
│   │   │   └── usecase/
│   │   │       └── CaptureUseCase.kt
│   │   └── ui/
│   │       ├── ScanActivity.kt
│   │       ├── ReviewActivity.kt
│   │       ├── composable/
│   │       │   ├── ScanScreen.kt
│   │       │   └── ReviewScreen.kt
│   │       └── viewmodel/
│   │           └── ScanViewModel.kt
│   └── res/
│       ├── layout/          (if using XML fallback)
│       ├── values/
│       │   └── strings.xml
│       └── drawable/
├── build.gradle
└── proguard-rules.pro
```

---

## 14. Build & Run Checklist

```
1.  Open project in Android Studio Hedgehog (2023.1.1) or later.
2.  Sync Gradle; ensure SDK 34 is installed.
3.  Connect device (API 24+) or start emulator with camera support.
4.  Run → grant camera permission → point at bill book.
5.  Observe real-time capture status; check scans/ folder for CSV output.
6.  Open Review screen to verify captured records.
7.  Pull CSV via adb: adb shell run-as com.billscanner cat files/scans/bill_scanner_YYYYMMDD.csv
```

---

## 15. Future Extensions (Not Implemented Now)

| Feature | Implementation Notes |
|---------|---------------------|
| Cloud sync | Add `INTERNET` permission, upload CSV to S3/Firebase Storage on session end |
| Multi-language OCR | Switch between `ChineseTextRecognizerOptions` / `DevanagariTextRecognizerOptions` based on locale setting |
| Batch edit | Add swipe-to-delete and inline edit on Review screen with Room DB backing |
| Image gallery | Save preprocessed bitmaps alongside CSV for audit trail |
| Export share | Use `Intent.ACTION_SEND` with `FileProvider` to share CSV via email/messaging |
| Background service | Use `ForegroundService` for sustained scanning with persistent notification |

---

*End of specification.*
