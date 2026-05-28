package com.chat.myapplication

import android.app.Application
import com.chat.effect.EffectLog
import com.chat.effect.EffectManager

/**
 * 应用入口。负责一次性配置 EffectManager（注入 Factory + 启用自动 stage）。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        EffectLog.enabled = true
        EffectManager.init(AppEffectPlayerFactory())
        EffectManager.enableAutoStage(this)
    }
}
