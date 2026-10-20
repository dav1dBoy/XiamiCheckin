package com.xiami.checkin.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiami.checkin.helper.AlarmActionHandler

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_USER_PRESENT) {
            Log.d("UnlockReceiver", "Device unlocked!")
            AlarmActionHandler.resumePendingLaunch(context)
        }
    }
}
