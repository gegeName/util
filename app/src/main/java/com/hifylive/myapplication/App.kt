package com.hifylive.myapplication

import android.app.Application
import com.lhj.effect.AppEffectPlayerFactory
import com.lhj.effect.EffectLog
import com.lhj.effect.EffectManager

/**
 * 应用入口。负责一次性配置 EffectManager（注入 Factory + 启用自动 stage）。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        EffectLog.enabled = true
        EffectManager.init(this, AppEffectPlayerFactory())
        EffectManager.enableAutoStage(this)
    }
}
