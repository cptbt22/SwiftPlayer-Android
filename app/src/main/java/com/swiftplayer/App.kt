package com.swiftplayer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 强制全 App 使用深色模式：SwiftPlayer 是深色设计，若跟随系统浅色，
        // Material 组件（MaterialAlertDialog / TextInputLayout / PopupMenu / BottomSheet）
        // 会解析为浅色表面，出现「白底白字」不可读问题。此处统一锁定深色，根治明暗混乱。
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
