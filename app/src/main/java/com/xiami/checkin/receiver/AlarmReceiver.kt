package com.xiami.checkin.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiami.checkin.helper.AlarmActionHandler
import com.xiami.checkin.helper.AlarmHelper
import com.xiami.checkin.helper.WifiNetworkHelper
import com.xiami.checkin.service.AlarmService
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d("AlarmReceiver", "Received action: $action")
        AlarmService.justStartForegroundService(context)
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmHelper.scheduleAllAlarms(context)
            return
        }

        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        if (AlarmHelper.isAlarmDay(context, dayOfWeek)) {
            val alarmTime = intent?.getStringExtra("ALARM_TIME") ?: ""
            if (alarmTime.isBlank()) {
                Log.w("AlarmReceiver", "Missing ALARM_TIME, ignoring unexpected broadcast.")
            } else {
                Log.d("AlarmReceiver", "Processing alarm for $alarmTime")
                checkWifiAndHandle(context, alarmTime)
            }
        } else {
            Log.d("AlarmReceiver", "Weekend reminders disabled, skipping.")
        }

        AlarmHelper.scheduleAllAlarms(context)
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
