package com.billscanner

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val FILE_NAME = "crash_log.txt"

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== CRASH ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===")
                pw.println("Thread: ${thread.name}")
                pw.println("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                pw.println()
                throwable.printStackTrace(pw)
                pw.println("=== END ===\n")

                val file = File(context.filesDir, FILE_NAME)
                file.appendText(sw.toString())
                Log.e(TAG, "Crash written to ${file.absolutePath}", throwable)
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun logError(context: Context, tag: String, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("=== ERROR ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===")
            pw.println("Tag: $tag")
            pw.println("Exception: ${throwable.javaClass.name}: ${throwable.message}")
            pw.println()
            throwable.printStackTrace(pw)
            pw.println("=== END ===\n")

            val file = File(context.filesDir, FILE_NAME)
            file.appendText(sw.toString())
        } catch (_: Exception) {
        }
    }

    fun readLog(context: Context): String? {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    fun clearLog(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }
}
