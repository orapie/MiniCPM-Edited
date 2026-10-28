package com.example.minicpm_v_demo

import android.app.Application
import android.util.Log
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper
import com.example.minicpm_v_demo.harness.data.database.DatabaseRuntime

class MiniCPMApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleManager.applyOnAppStart(this)
        try {
            AssetBootstrapper.bootstrap(this)
            DatabaseRuntime.installation?.let { Log.i("DatabaseInstallation", it.toLogLine()) }
        } catch (error: Exception) {
            Log.e("MiniCPMApplication", "Failed to bootstrap harness assets", error)
        }
    }
}
