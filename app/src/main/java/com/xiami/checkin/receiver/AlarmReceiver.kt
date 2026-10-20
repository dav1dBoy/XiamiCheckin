package com.xiami.checkin.receiver

import android.content.BroadcastReceiver
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiami.checkin.helper.AlarmActionHandler
import com.xiami.checkin.helper.AlarmHelper
import com.xiami.checkin.helper.WifiNetworkHelper
import com.xiami.checkin.service.AlarmService

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d("AlarmReceiver", "Received action: $action")
        if (action in setOf(
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
            )) {
            AlarmHelper.scheduleAllAlarms(context)
            return
        }
        if (action != AlarmHelper.ACTION_ALARM) return
        val alarmTime = intent.getStringExtra(AlarmHelper.EXTRA_ALARM_TIME).orEmpty()
        val claimed = AlarmHelper.claimAlarm(
            context, alarmTime, intent.getLongExtra(AlarmHelper.EXTRA_TRIGGER_AT, 0)
        )
        // 先安排下一次，当前的 WiFi、锁屏或后台启动失败不影响第二天。
        AlarmHelper.scheduleAllAlarms(context)
        if (!claimed) return
        AlarmService.justStartForegroundService(context)
        checkWifiAndHandle(context, alarmTime)
    }

    private fun checkWifiAndHandle(context: Context, alarmTime: String) {
        if (WifiNetworkHelper.isConnectedToTargetWifi(context)) {
            Log.d("AlarmReceiver", "Already on ${WifiNetworkHelper.TARGET_SSID} WiFi.")
            AlarmActionHandler.triggerOpenOrNotify(context, alarmTime)
            return
        }

        Log.d("AlarmReceiver", "Not on target WiFi, notify and start monitoring.")
        AlarmActionHandler.sendWaitingForWifiNotification(context, alarmTime)
        AlarmService.startWifiMonitoring(context, alarmTime)
    }
}
