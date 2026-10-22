package com.oska.loudspeak

import android.app.Application
import com.oska.loudspeak.core.CrashReporter

class LoudSpeakApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)   // 全局崩溃捕获 → error_reports/*.txt（调试用）
    }
}
