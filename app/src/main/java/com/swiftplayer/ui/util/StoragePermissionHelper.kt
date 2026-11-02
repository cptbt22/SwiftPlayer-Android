package com.swiftplayer.ui.util

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.swiftplayer.R

/**
 * 存储权限辅助工具（D4 决策基线）
 *
 * 方案以 MANAGE_EXTERNAL_STORAGE（所有文件访问）为主：
 * - Android 11+（R）：检查 [Environment.isExternalStorageManager]，未授权时引导系统设置；
 * - Android 10 及以下：走常规 READ_EXTERNAL_STORAGE 运行时权限；
 * - 未授权时降级为媒体库可见范围（READ_MEDIA_VIDEO），并给出提示。
 *
 * 不引入 SAF。
 */
class StoragePermissionHelper(private val activity: Activity) {

    /** 是否拥有「所有文件访问」或等价能力 */
    fun hasFullAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 需要申请的运行时权限（< Android R 返回 READ_EXTERNAL_STORAGE，其余返回 null） */
    fun requiredRuntimePermission(): String? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Manifest.permission.READ_EXTERNAL_STORAGE
        } else {
            null
        }

    /** Android 11+ 且未授权「所有文件访问」时为 true */
    fun needsAllFilesGuide(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    /** 跳转系统「所有文件访问」设置页 */
    fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                // 忽略：部分 ROM 无该设置页
            }
        }
    }

    /** 未授权时的降级提示文案资源 */
    fun degradeHintRes(): Int = R.string.hint_grant_all_files
}
