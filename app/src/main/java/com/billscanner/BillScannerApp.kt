package com.billscanner

import android.app.Application

class BillScannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
