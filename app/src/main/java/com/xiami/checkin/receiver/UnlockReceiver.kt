package com.xiami.checkin.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiami.checkin.helper.FeishuLauncher

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_USER_PRESENT) {
            Log.d("UnlockReceiver", "Device unlocked!")
            val prefs = context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
            val pendingOpen = prefs.getBoolean("PENDING_OPEN_FEISHU", false)

            if (pendingOpen) {
                Log.d("UnlockReceiver", "Pending open found, launching Feishu...")
                FeishuLauncher.openFeishu(context)
                // 由过渡页实际发起飞书启动后再消费标记，避免过早清除。
            }
        }
    }
}
