package com.xiami.checkin.helper

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xiami.checkin.R
import com.xiami.checkin.ui.page.MainActivity
import com.xiami.checkin.service.AlarmService
import androidx.core.net.toUri
import java.time.LocalTime
import java.time.LocalDate

object AlarmActionHandler {
    private const val TAG = "AlarmActionHandler"
    private const val REMINDER_CHANNEL_ID = "XiamiCheckInReminderChannel_v9"
    private const val REMINDER_CHANNEL_COMMON_ID = "XiamiCheckInReminderChannel_common_v1"
    private const val WAIT_WIFI_CHANNEL_ID = "XiamiCheckInWaitWifiChannel_v1"
    private const val REMINDER_NOTIFICATION_ID = 2
    private const val WAIT_WIFI_NOTIFICATION_ID = 3
    private const val ALARM_PREFS = "AlarmPrefs"
    private const val PENDING_OPEN_FEISHU_KEY = "PENDING_OPEN_FEISHU"
    private const val PENDING_TIME_KEY = "pending_alarm_time"
    private const val PENDING_DATE_KEY = "pending_alarm_date"

    fun triggerOpenOrNotify(context: Context, alarmTime: String) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PENDING_OPEN_FEISHU_KEY, true)
            .putString(PENDING_TIME_KEY, alarmTime)
            .putString(PENDING_DATE_KEY, LocalDate.now().toString())
            .apply()
        val keyguardManager = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = keyguardManager.isKeyguardLocked
        Log.d(TAG, "Alarm ready at $alarmTime, device locked: $isLocked")

        // 后台 startActivity 可能被系统静默拒绝，必须先保存待办并提供通知入口。
        sendReminderNotification(
            appContext, alarmTime, defaultSound = LocalTime.now().hour >= 12
        )
        // 锁屏只保留提醒，用户手动解锁后由 UnlockReceiver 继续。
        if (!isLocked) {
            FeishuLauncher.openFeishu(context, scheduled = true)
        }
    }

    fun resumePendingLaunch(context: Context) {
        if (canLaunchScheduled(context)) {
            FeishuLauncher.openFeishu(context, scheduled = true)
        }
    }

    fun canLaunchScheduled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PENDING_OPEN_FEISHU_KEY, false)) return false
        // 不在第二天解锁时补执行昨天已过期的提醒。
        if (prefs.getString(PENDING_DATE_KEY, null) != LocalDate.now().toString()) {
            cancelPendingLaunch(context)
            return false
        }
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        if (!WifiNetworkHelper.isConnectedToTargetWifi(context)) {
            val time = prefs.getString(PENDING_TIME_KEY, "").orEmpty()
            sendWaitingForWifiNotification(context, time)
            AlarmService.startWifiMonitoring(context, time)
            return false
        }
        return true
    }

    fun sendReminderNotification(context: Context, time: String, defaultSound: Boolean) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundUri = if(defaultSound)
            RingtoneManager.getDefaultUri (RingtoneManager.TYPE_NOTIFICATION)
        else (("android.resource://" + context.packageName + "/" + R.raw.alarm2).toUri())
        val channelId = if(defaultSound) REMINDER_CHANNEL_COMMON_ID else REMINDER_CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "打卡提醒通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "已连接 ${WifiNetworkHelper.TARGET_SSID} 或到达打卡时间后的提醒"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 通知直接打开 Activity，避免 Android 12+ 的通知跳板限制。
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            FeishuLauncher.createLaunchIntent(context, scheduled = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_checkin)
            .setContentTitle("打卡时间到了！($time)")
            .setContentText("等待切换飞书；若未自动打开，请手动解锁后点击此通知")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // 点击后也保留，只有真正发出飞书启动请求才清除，失败时仍可重试。
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSound(soundUri)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(REMINDER_NOTIFICATION_ID, notification)
    }

    internal fun onFeishuLaunchDispatched(context: Context) {
        cancelPendingLaunch(context)
    }

    fun cancelPendingLaunch(context: Context) {
        context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PENDING_OPEN_FEISHU_KEY, false)
            .remove(PENDING_TIME_KEY).remove(PENDING_DATE_KEY).apply()
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 只清理本次提醒，保留前台监控服务通知。
        notificationManager.cancel(REMINDER_NOTIFICATION_ID)
        notificationManager.cancel(WAIT_WIFI_NOTIFICATION_ID)
    }

    fun sendWaitingForWifiNotification(context: Context, time: String) {
        val appContext = context.applicationContext
        wakeUpScreen(appContext)

        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WAIT_WIFI_CHANNEL_ID,
                "等待连接 WiFi",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "提醒连接 ${WifiNetworkHelper.TARGET_SSID} WiFi 以完成打卡"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val mainIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            1,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, WAIT_WIFI_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_checkin)
            .setContentTitle("打卡时间到了！($time)")
            .setContentText("请连接 WiFi「${WifiNetworkHelper.TARGET_SSID}」，连接后将自动打开飞书")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(WAIT_WIFI_NOTIFICATION_ID, notification)
    }

    @SuppressLint("InvalidWakeLockTag")
    private fun wakeUpScreen(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "XiamiCheckIn:WakeLock"
        )
        wakeLock.acquire(3_000)
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }
}
