package com.example.minicpm_v_demo

import android.app.Application
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper

class MiniCPMApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleManager.applyOnAppStart(this)
        AssetBootstrapper.startAsync(this)
    }
}
