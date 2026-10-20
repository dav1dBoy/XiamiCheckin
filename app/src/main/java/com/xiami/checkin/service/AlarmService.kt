package com.xiami.checkin.service

import android.app.Notification
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xiami.checkin.helper.AlarmActionHandler
import com.xiami.checkin.helper.AlarmHelper
import com.xiami.checkin.helper.AssistantReturnTimer
import com.xiami.checkin.helper.WifiNetworkHelper
import com.xiami.checkin.R
import com.xiami.checkin.receiver.UnlockReceiver
import com.xiami.checkin.ui.page.MainActivity
import java.time.LocalDate

class AlarmService : Service() {

    private var unlockReceiver: UnlockReceiver? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val returnTimer = AssistantReturnTimer()
    private val returnToAssistantRunnable = Runnable { returnToAssistantIfReady() }
    private var pendingAlarmTime: String? = null
    private val wifiRetryIntervalMs = 15_000L
    private val wifiRetryRunnable = object : Runnable {
        override fun run() {
            val t = pendingAlarmTime
            if (!t.isNullOrBlank()) {
                tryCompleteAlarmIfOnWifi(t)
                if (pendingAlarmTime != null) {
                    mainHandler.postDelayed(this, wifiRetryIntervalMs)
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "XiamiCheckInServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START_WIFI_MONITORING = "com.xiami.checkin.action.START_WIFI_MONITORING"
        private const val ACTION_STOP_MONITORING = "com.xiami.checkin.action.STOP_MONITORING"
        private const val ACTION_SCHEDULE_RETURN = "com.xiami.checkin.action.SCHEDULE_RETURN"
        private const val ACTION_CANCEL_RETURN = "com.xiami.checkin.action.CANCEL_RETURN"
        private const val EXTRA_FEISHU_OPENED_AT = "feishu_opened_at"
        const val ACTION_MONITORING_STATE_CHANGED = "com.xiami.checkin.action.MONITORING_STATE_CHANGED"
        const val EXTRA_IS_MONITORING = "extra_is_monitoring"
        private const val EXTRA_ALARM_TIME = "extra_alarm_time"
        private const val PREFS_NAME = "AlarmServicePrefs"
        private const val KEY_WIFI_MONITORING_ACTIVE = "wifi_monitoring_active"
        private const val KEY_PENDING_TIME = "pending_time"
        private const val KEY_PENDING_DATE = "pending_date"

        fun justStartForegroundService(context: Context) {
            tryStartService(context, Intent(context, AlarmService::class.java))
        }

        fun startWifiMonitoring(context: Context, alarmTime: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_TIME, alarmTime)
                .putString(KEY_PENDING_DATE, LocalDate.now().toString()).apply()
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_START_WIFI_MONITORING
                putExtra(EXTRA_ALARM_TIME, alarmTime)
            }
            tryStartService(context, intent)
        }

        fun stopMonitoring(context: Context) {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            tryStartService(context, intent)
        }

        fun scheduleReturnToAssistant(context: Context, feishuOpenedAt: Long) {
            tryStartService(context, Intent(context, AlarmService::class.java).apply {
                action = ACTION_SCHEDULE_RETURN
                putExtra(EXTRA_FEISHU_OPENED_AT, feishuOpenedAt)
            })
        }

        fun cancelReturnToAssistant(context: Context) {
            tryStartService(context, Intent(context, AlarmService::class.java).apply {
                action = ACTION_CANCEL_RETURN
            })
        }

        private fun tryStartService(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (error: IllegalStateException) {
                // 非精确闹钟或厂商限制可能不允许启动；保留通知供用户打开助手恢复。
                Log.w("AlarmService", "Background service start denied", error)
            } catch (error: SecurityException) {
                Log.w("AlarmService", "Foreground service permission denied", error)
            }
        }

        fun isWifiMonitoringActive(context: Context): Boolean {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_WIFI_MONITORING_ACTIVE, false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerUnlockReceiver()
        AlarmHelper.scheduleAllAlarms(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmTime = intent?.getStringExtra(EXTRA_ALARM_TIME)
        val notification = when (intent?.action) {
            ACTION_START_WIFI_MONITORING -> createNotification(
                "未连接 ${WifiNetworkHelper.TARGET_SSID} WiFi，正在等待连接…",
                true
            )
            ACTION_STOP_MONITORING -> createNotification("已关闭 WiFi 监控")
            else -> createNotification()
        }
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_SCHEDULE_RETURN -> {
                scheduleReturnToAssistantInternal(intent.getLongExtra(EXTRA_FEISHU_OPENED_AT, 0))
                return START_STICKY
            }
            ACTION_CANCEL_RETURN -> cancelReturnToAssistantInternal()
        }

        if (intent?.action == ACTION_STOP_MONITORING) {
            cancelReturnToAssistantInternal()
            stopWifiMonitoringInternal()
            AlarmActionHandler.cancelPendingLaunch(this)
            return START_STICKY
        }

        if (intent?.action == ACTION_START_WIFI_MONITORING && !alarmTime.isNullOrBlank()) {
            startWifiMonitoringInternal(alarmTime)
        } else if (pendingAlarmTime == null) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val savedTime = prefs.getString(KEY_PENDING_TIME, null)
            if (!savedTime.isNullOrBlank() &&
                prefs.getString(KEY_PENDING_DATE, null) == LocalDate.now().toString()) {
                startWifiMonitoringInternal(savedTime)
            } else {
                stopWifiMonitoringInternal()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        cancelReturnToAssistantInternal()
        stopWifiMonitoringInternal(clearPending = false)
        unregisterUnlockReceiver()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleReturnToAssistantInternal(feishuOpenedAt: Long) {
        cancelReturnToAssistantInternal()
        val now = SystemClock.elapsedRealtime()
        if (feishuOpenedAt <= 0 || feishuOpenedAt > now) return
        returnTimer.schedule(feishuOpenedAt)
        mainHandler.postDelayed(returnToAssistantRunnable, returnTimer.remainingDelay(now) ?: return)
        updateForegroundNotification("已请求打开飞书，约 5 秒后返回助手", false)
    }

    private fun cancelReturnToAssistantInternal() {
        mainHandler.removeCallbacks(returnToAssistantRunnable)
        returnTimer.cancel()
    }

    private fun returnToAssistantIfReady() {
        val deviceReady = getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!returnTimer.consumeIfDue(SystemClock.elapsedRealtime(), deviceReady)) {
            updateForegroundNotification("自动返回已取消；可点击通知返回助手", false)
            return
        }
        try {
            startActivity(createAssistantIntent(this))
            // 部分系统会静默拦截后台启动；常驻通知的点击入口仍可返回助手。
            updateForegroundNotification("已请求返回助手；未返回时可点击此通知", false)
            Log.d("AlarmService", "Requested return to assistant after Feishu stay")
        } catch (error: ActivityNotFoundException) {
            Log.e("AlarmService", "Assistant activity unavailable", error)
            updateForegroundNotification("无法自动返回助手，请点击此通知", false)
        } catch (error: SecurityException) {
            Log.w("AlarmService", "Return to assistant denied", error)
            updateForegroundNotification("系统限制自动返回，请点击此通知返回助手", false)
        }
    }

    private fun createAssistantIntent(context: Context) =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    private fun registerUnlockReceiver() {
        if (unlockReceiver == null) {
            unlockReceiver = UnlockReceiver()
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(this, unlockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun unregisterUnlockReceiver() {
        unlockReceiver?.let {
            unregisterReceiver(it)
            unlockReceiver = null
        }
    }

    private fun createNotification(
        contentText: String = "到时间会提醒打卡",
        showStopAction: Boolean = false
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.monitoring_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_checkin)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(
                this, 1002, createAssistantIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))

        if (showStopAction) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "关闭监控",
                createStopMonitoringPendingIntent()
            )
        }

        return builder.build()
    }

    private fun createStopMonitoringPendingIntent(): PendingIntent {
        val intent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        return PendingIntent.getService(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateForegroundNotification(contentText: String, showStopAction: Boolean) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(
            NOTIFICATION_ID,
            createNotification(contentText, showStopAction)
        )
    }

    private fun updateMonitoringState(isMonitoring: Boolean) {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_WIFI_MONITORING_ACTIVE, isMonitoring).apply()
        sendBroadcast(
            Intent(ACTION_MONITORING_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_IS_MONITORING, isMonitoring)
        )
    }

    private fun startWifiMonitoringInternal(alarmTime: String) {
        stopWifiMonitoringInternal(clearPending = false)
        pendingAlarmTime = alarmTime
        updateMonitoringState(true)
        updateForegroundNotification("未连接 ${WifiNetworkHelper.TARGET_SSID} WiFi，正在等待连接…", true)

        tryCompleteAlarmIfOnWifi(alarmTime)
        if (pendingAlarmTime == null) return

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { tryCompleteAlarmIfOnWifi(alarmTime) }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                mainHandler.post { tryCompleteAlarmIfOnWifi(alarmTime) }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    if (pendingAlarmTime != alarmTime) return@post
                    updateForegroundNotification(
                        "未连接 ${WifiNetworkHelper.TARGET_SSID} WiFi，正在等待连接…",
                        true
                    )
                }
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
        mainHandler.postDelayed(wifiRetryRunnable, wifiRetryIntervalMs)
    }

    private fun tryCompleteAlarmIfOnWifi(alarmTime: String) {
        // 已取消/完成的 NetworkCallback 仍可能排队，不能再次触发切换。
        if (pendingAlarmTime != alarmTime) return
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_PENDING_DATE, null) != LocalDate.now().toString()) {
            stopWifiMonitoringInternal()
            updateForegroundNotification("上次 WiFi 等待已过期，等待下次提醒", false)
            return
        }
        if (!WifiNetworkHelper.hasWifiSsidReadPermission(this)) {
            Log.w("AlarmService", "Still waiting: no permission to read WiFi SSID")
            updateForegroundNotification(
                "无法读取 WiFi 名称，请在应用内授予附近设备或位置权限",
                true
            )
            return
        }
        if (!WifiNetworkHelper.isConnectedToTargetWifi(this)) {
            Log.d("AlarmService", "Still not on ${WifiNetworkHelper.TARGET_SSID}")
            return
        }

        Log.d("AlarmService", "Connected to ${WifiNetworkHelper.TARGET_SSID}, triggering alarm flow")
        pendingAlarmTime = null
        stopWifiMonitoringInternal()
        AlarmActionHandler.triggerOpenOrNotify(this, alarmTime)
        updateForegroundNotification("已连接到 ${WifiNetworkHelper.TARGET_SSID}", false)
    }

    private fun stopWifiMonitoringInternal(clearPending: Boolean = true) {
        mainHandler.removeCallbacks(wifiRetryRunnable)
        val cm = connectivityManager
        val cb = networkCallback
        if (cm != null && cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        connectivityManager = null
        networkCallback = null
        pendingAlarmTime = null
        if (clearPending) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(KEY_PENDING_TIME).remove(KEY_PENDING_DATE).apply()
        }
        updateMonitoringState(false)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitoring_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}
