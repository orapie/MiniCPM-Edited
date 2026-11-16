package com.example.minicpm_v_demo

import android.app.Application
import com.example.minicpm_v_demo.harness.data.AssetBootstrapper

/** 在首个界面创建前应用已保存的语言设置，并启动异步数据库初始化。 */
class MiniCPMApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleManager.applyOnAppStart(this)
        AssetBootstrapper.startAsync(this)
    }
}
