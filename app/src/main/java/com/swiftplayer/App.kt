package com.swiftplayer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.swiftplayer.data.net.DnsProvider
import java.io.InputStream

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 强制全 App 使用深色模式：SwiftPlayer 是深色设计，若跟随系统浅色，
        // Material 组件（MaterialAlertDialog / TextInputLayout / PopupMenu / BottomSheet）
        // 会解析为浅色表面，出现「白底白字」不可读问题。此处统一锁定深色，根治明暗混乱。
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        instance = this

        // 图片加载复用带 DoH 的 OkHttp 网络栈。
        // 不做这一步，Glide 会退回 HttpUrlConnection，用自己的系统 DNS 解析图片域名：
        // API 请求（走 DnsProvider.smart）能连通，封面却因解析失败成片空白。
        Glide.get(this).registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(DnsProvider.imageClient)
        )
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
