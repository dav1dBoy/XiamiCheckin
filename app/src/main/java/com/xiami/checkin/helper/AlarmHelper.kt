package com.xiami.checkin.helper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.xiami.checkin.receiver.AlarmReceiver
import java.time.LocalDate
import java.time.ZonedDateTime

object AlarmHelper {
    const val ACTION_ALARM = "com.xiami.checkin.action.ALARM"
    const val EXTRA_ALARM_TIME = "ALARM_TIME"
    const val EXTRA_TRIGGER_AT = "TRIGGER_AT"
    private const val PREFS_NAME = "AlarmPrefs"
    private const val ALARM_TIMES_KEY = "AlarmTimes"
    private const val WEEKEND_ENABLED_KEY = "weekend_enabled"
    private const val LAST_FIRED_PREFIX = "last_fired_"

    fun getAlarmTimes(context: Context): List<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(ALARM_TIMES_KEY, emptySet())?.toList()?.sorted().orEmpty()

    fun isWeekendEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(WEEKEND_ENABLED_KEY, false)

    fun setWeekendEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(WEEKEND_ENABLED_KEY, enabled).apply()
        scheduleAllAlarms(context)
    }

    fun canScheduleExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun addAlarmTime(context: Context, time: String) {
        if (AlarmSchedule.parseTime(time) == null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val times = getAlarmTimes(context).toMutableSet()
        if (times.add(time)) {
            prefs.edit().putStringSet(ALARM_TIMES_KEY, times).apply()
            scheduleAllAlarms(context)
        }
    }

    fun removeAlarmTime(context: Context, time: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val times = getAlarmTimes(context).toMutableSet()
        if (times.remove(time)) {
            prefs.edit().putStringSet(ALARM_TIMES_KEY, times)
                .remove(LAST_FIRED_PREFIX + time).apply()
            cancelAlarm(context, time, ACTION_ALARM)
            cancelAlarm(context, time, null)
        }
    }

    /** 使用系统持有的 PendingIntent；进程退出后仍能在下一个时间点收到广播。 */
    fun scheduleAllAlarms(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = ZonedDateTime.now()
        for (time in getAlarmTimes(context)) {
            // 清理旧版本没有 action 的闹钟。
            cancelAlarm(context, time, null)
            val lastFired = prefs.getString(LAST_FIRED_PREFIX + time, null)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val next = AlarmSchedule.nextTrigger(time, now, isWeekendEnabled(context), lastFired)
                ?: continue
            val triggerAt = next.toInstant().toEpochMilli()
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_ALARM
                putExtra(EXTRA_ALARM_TIME, time)
                putExtra(EXTRA_TRIGGER_AT, triggerAt)
            }
            val operation = PendingIntent.getBroadcast(
                context, time.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (canScheduleExactAlarms(context)) {
                try {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
                    Log.d("AlarmHelper", "Scheduled exact alarm: $time at $next")
                    continue
                } catch (error: SecurityException) {
                    Log.w("AlarmHelper", "Exact alarm access unavailable", error)
                }
            }
            // 未授权时保留提醒能力；主界面明确提示可能延迟。
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            Log.w("AlarmHelper", "Scheduled inexact alarm: $time at $next")
        }
    }

    @Synchronized
    fun claimAlarm(context: Context, time: String, triggerAt: Long): Boolean {
        if (time !in getAlarmTimes(context)) return false
        val now = ZonedDateTime.now()
        if (!AlarmSchedule.isDeliveryValid(time, triggerAt, now, isWeekendEnabled(context))) {
            return false
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = now.toLocalDate().toString()
        val key = LAST_FIRED_PREFIX + time
        if (prefs.getString(key, null) == today) return false
        prefs.edit().putString(key, today).apply()
        return true
    }

    private fun cancelAlarm(context: Context, time: String, action: String?) {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(action)
        val operation = PendingIntent.getBroadcast(
            context, time.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(operation)
        operation.cancel()
    }
}
