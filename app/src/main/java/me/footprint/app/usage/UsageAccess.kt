package me.footprint.app.usage

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * "使用情况访问"特殊权限的检测与跳转。
 *
 * 文档 16.4 第 1 条：这个权限**不是弹窗给的**，requestPermissions() 对它无效。
 * 只能跳「设置 → 特殊应用权限 → 使用情况访问」，用户返回后再用 AppOpsManager 重新检测。
 */
object UsageAccess {

    /**
     * 是否已授权。
     * - API 29+ 用 unsafeCheckOpNoThrow（不再需要主线程之外的限制，直接在后台调也安全）
     * - API 28 及以下用 checkOpNoThrow
     * 注意两个方法都要传【自己的 uid】，传错了账号下别的应用会干扰结果。
     */
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        } catch (e: SecurityException) {
            // 个别 ROM 会抛，按未授权处理更安全（宁可显示空态，也不要假装有数据）
            AppOpsManager.MODE_ERRORED
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 跳系统「使用情况访问」设置页。部分 ROM 没有这个页面，退回到应用详情页。 */
    fun openSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            // 少数 ROM 不认 ACTION_USAGE_ACCESS_SETTINGS，退一步到本应用详情页
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
        for (i in intents) {
            try {
                context.startActivity(i)
                return
            } catch (_: Exception) {
                // 试下一个
            }
        }
    }
}
