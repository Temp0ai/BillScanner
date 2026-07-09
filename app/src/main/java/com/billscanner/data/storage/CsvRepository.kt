package com.billscanner.data.storage

import android.content.Context
import android.util.Log
import com.billscanner.domain.parser.ExtractedCustomer
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class CsvRepository(private val context: Context) {

    companion object {
        private const val TAG = "CsvRepository"
        private const val CSV_HEADER = "timestamp,name,phone,total_amount,confidence\n"
    }

    private fun getScansDir(): File {
        val dir = File(context.filesDir, "scans")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getTodayFile(): File {
        val dateStamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return File(getScansDir(), "bill_scanner_$dateStamp.csv")
    }

    private fun getAllCsvFiles(): List<File> {
        return getScansDir().listFiles { f -> f.extension == "csv" }?.toList() ?: emptyList()
    }

    private fun loadExistingPhones(): Set<String> {
        val phones = mutableSetOf<String>()
        for (file in getAllCsvFiles()) {
            try {
                file.readLines().drop(1).forEach { line ->
                    val cols = line.split(",")
                    if (cols.size >= 3) {
                        phones.add(cols[2].trim())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading file: ${file.name}", e)
            }
        }
        return phones
    }

    fun append(customers: List<ExtractedCustomer>): Int {
        val existing = loadExistingPhones().toMutableSet()
        val file = getTodayFile()
        val isNew = !file.exists()

        var written = 0
        try {
            FileWriter(file, true).use { writer ->
                if (isNew) writer.write(CSV_HEADER)

                for (c in customers) {
                    if (c.phone !in existing) {
                        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                        val safeName = c.name.replace(",", ";").replace("\n", " ").replace("\r", "")
                        val confidenceStr = String.format(Locale.US, "%.2f", c.confidence)
                        writer.write("$ts,$safeName,${c.phone},${c.totalAmount},$confidenceStr\n")
                        existing.add(c.phone)
                        written++
                    }
                }
            }
            Log.d(TAG, "Appended $written records to ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write CSV", e)
        }
        return written
    }

    fun readCurrentSession(): List<ExtractedCustomer> {
        val file = getTodayFile()
        if (!file.exists()) return emptyList()

        return try {
            file.readLines().drop(1).mapNotNull { line ->
                val cols = line.split(",")
                if (cols.size >= 3) {
                    ExtractedCustomer(
                        name = cols[1].trim(),
                        phone = cols[2].trim(),
                        totalAmount = cols.getOrNull(3)?.trim() ?: "",
                        confidence = cols.getOrNull(4)?.trim()?.toFloatOrNull() ?: 0f
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read CSV", e)
            emptyList()
        }
    }

    fun getCurrentFilePath(): String? {
        val file = getTodayFile()
        return if (file.exists()) file.absolutePath else null
    }

    fun deleteAll() {
        getAllCsvFiles().forEach { file ->
            if (file.delete()) {
                Log.d(TAG, "Deleted ${file.name}")
            }
        }
    }
}
