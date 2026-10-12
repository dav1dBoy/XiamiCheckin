package com.xiami.checkin.ui.page

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.xiami.checkin.R
import com.xiami.checkin.helper.FeishuLauncher

/** 每次唤起飞书前，先用不透明页面让飞书退到后台。 */
class FeishuLaunchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var launchDispatched = false
    private val launchRunnable = Runnable {
        if (canLaunch()) {
            launchDispatched = true
            FeishuLauncher.launchFromForeground(this)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setText(R.string.preparing_feishu)
            textSize = 18f
            gravity = Gravity.CENTER
        })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        scheduleLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 解锁广播与通知点击可能同时到达，复用当前过渡页和同一个延迟任务。
        scheduleLaunch()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        scheduleLaunch()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(launchRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(launchRunnable)
        super.onDestroy()
    }

    private fun canLaunch(): Boolean =
        resumed && hasWindowFocus() && !isFinishing && !launchDispatched &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun scheduleLaunch() {
        handler.removeCallbacks(launchRunnable)
        if (canLaunch()) {
            // 从页面真正获得焦点后计时，给飞书留出处理退后台事件的时间。
            Log.d("FeishuLaunchActivity", "Foreground ready; waiting before opening Feishu")
            handler.postDelayed(launchRunnable, BACKGROUND_SETTLE_DELAY_MS)
        }
    }

    companion object {
        private const val BACKGROUND_SETTLE_DELAY_MS = 1_500L
    }
}
