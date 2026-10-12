package com.xiami.checkin.helper

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.xiami.checkin.ui.page.FeishuLaunchActivity

object FeishuLauncher {
    private const val TAG = "FeishuLauncher"
    private const val PACKAGE_NAME = "com.ss.android.lark"

    fun createLaunchIntent(context: Context): Intent =
        Intent(context, FeishuLaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    fun openFeishu(context: Context) {
        // 先让不透明的助手页面进入前台，使已经在前台的飞书也能完成一次后台切换。
        context.startActivity(createLaunchIntent(context))
    }

    internal fun launchFromForeground(activity: Activity) {
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
        if (launchIntent == null) {
            showLaunchError(activity, "未找到飞书应用，请检查是否安装")
            return
        }

        // 恢复飞书任务即可；CLEAR_TOP 会清掉飞书内部页面，不能替代前后台切换。
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(launchIntent)
            AlarmActionHandler.onFeishuLaunchDispatched(activity)
            Log.d(TAG, "Feishu launch dispatched after foreground transition")
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "Feishu activity unavailable", error)
            showLaunchError(activity, "无法打开飞书，请检查是否安装或已停用")
        } catch (error: SecurityException) {
            Log.e(TAG, "Feishu launch denied", error)
            showLaunchError(activity, "系统禁止打开飞书，请检查应用权限")
        }
    }

    private fun showLaunchError(context: Context, message: String) {
        Log.e(TAG, message)
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
