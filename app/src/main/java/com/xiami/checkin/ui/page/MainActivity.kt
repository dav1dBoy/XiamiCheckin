package com.xiami.checkin.ui.page

import android.Manifest
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.xiami.checkin.R
import com.xiami.checkin.helper.AlarmHelper
import com.xiami.checkin.helper.FeishuLauncher
import com.xiami.checkin.helper.WifiNetworkHelper
import com.xiami.checkin.service.AlarmService
import com.xiami.checkin.ui.theme.XiamiCheckInTheme
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var isWifiMonitoringActive by mutableStateOf(false)
    private var exactAlarmAllowed by mutableStateOf(false)
    private var overlayAllowed by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(false)

    private val monitoringStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlarmService.ACTION_MONITORING_STATE_CHANGED) {
                isWifiMonitoringActive = intent.getBooleanExtra(
                    AlarmService.EXTRA_IS_MONITORING,
                    AlarmService.isWifiMonitoringActive(this@MainActivity)
                )
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        continuePermissionFlow()
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        checkOverlayPermission()
    }

    private val appSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkOverlayPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("MainActivity", "MainActivity created")
        enableEdgeToEdge()
        isWifiMonitoringActive = AlarmService.isWifiMonitoringActive(this)
        val currentTimes = AlarmHelper.getAlarmTimes(this)
        if (!currentTimes.contains("09:15")) {
            AlarmHelper.addAlarmTime(this, "09:15")
            AlarmHelper.addAlarmTime(this, "18:50")
        }
        initUI()

        requestWifiRelatedPermissions()
        startAlarmService()
        AlarmHelper.scheduleAllAlarms(this)
    }

    override fun onStart() {
        super.onStart()
        isWifiMonitoringActive = AlarmService.isWifiMonitoringActive(this)
        ContextCompat.registerReceiver(
            this,
            monitoringStateReceiver,
            IntentFilter(AlarmService.ACTION_MONITORING_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        exactAlarmAllowed = AlarmHelper.canScheduleExactAlarms(this)
        overlayAllowed = Settings.canDrawOverlays(this)
        notificationsAllowed = NotificationManagerCompat.from(this).areNotificationsEnabled()
        // 从权限设置回来或进程重建时恢复系统闹钟。
        AlarmHelper.scheduleAllAlarms(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 真正回到助手时取消旧倒计时，避免随后再打开别的页面被拉回。
            AlarmService.cancelReturnToAssistant(this)
        }
    }

    override fun onStop() {
        unregisterReceiver(monitoringStateReceiver)
        super.onStop()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun initUI(){
        setContent {
            XiamiCheckInTheme {
                var alarmTimes by remember { mutableStateOf(AlarmHelper.getAlarmTimes(this)) }
                var weekendEnabled by remember {
                    mutableStateOf(AlarmHelper.isWeekendEnabled(this))
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.app_name)) })
                    },
                    floatingActionButton = {
                        FloatingActionButton(onClick = {
                            showTimePicker(this) { newTime ->
                                AlarmHelper.addAlarmTime(this, newTime)
                                alarmTimes = AlarmHelper.getAlarmTimes(this)
                            }
                        }) {
                            Text("+", fontSize = 24.sp)
                        }
                    }
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        alarmTimes = alarmTimes,
                        weekendEnabled = weekendEnabled,
                        onWeekendEnabledChange = { enabled ->
                            AlarmHelper.setWeekendEnabled(this, enabled)
                            weekendEnabled = enabled
                        },
                        isWifiMonitoringActive = isWifiMonitoringActive,
                        exactAlarmAllowed = exactAlarmAllowed,
                        overlayAllowed = overlayAllowed,
                        notificationsAllowed = notificationsAllowed,
                        onRequestExactAlarm = { requestExactAlarmPermission() },
                        onDeleteTime = { time ->
                            AlarmHelper.removeAlarmTime(this, time)
                            alarmTimes = AlarmHelper.getAlarmTimes(this)
                        },
                        onOpenFeishu = { FeishuLauncher.openFeishu(this) },
                        onRequestPermissions = { requestWifiRelatedPermissions() },
                        onStopMonitoring = {
                            AlarmService.stopMonitoring(this)
                            isWifiMonitoringActive = false
                        }
                    )
                }
            }
        }
    }
    private fun showTimePicker(context: Context, onTimeSelected: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        TimePickerDialog(
            context,
            { _, hour, minute ->
                val time = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
                onTimeSelected(time)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun requestWifiRelatedPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.distinct().toTypedArray())
        } else {
            continuePermissionFlow()
        }
    }

    private fun continuePermissionFlow() {
        if (needsBackgroundLocationPermission()) {
            requestBackgroundLocationPermission()
        } else {
            checkOverlayPermission()
        }
    }

    private fun needsBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestBackgroundLocationPermission() {
        if (!needsBackgroundLocationPermission()) {
            checkOverlayPermission()
            return
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }

        Toast.makeText(
            this,
            "请在接下来的设置页面中将位置信息权限改为“始终允许”，这样后台才能读取 WiFi 名称。",
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        appSettingsLauncher.launch(intent)
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "请授予“在其他应用上层显示”权限", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAlarmService() {
        AlarmService.justStartForegroundService(this)
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !AlarmHelper.canScheduleExactAlarms(this)) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }
    }

}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    alarmTimes: List<String>,
    weekendEnabled: Boolean,
    onWeekendEnabledChange: (Boolean) -> Unit,
    isWifiMonitoringActive: Boolean,
    exactAlarmAllowed: Boolean,
    overlayAllowed: Boolean,
    notificationsAllowed: Boolean,
    onRequestExactAlarm: () -> Unit,
    onDeleteTime: (String) -> Unit,
    onOpenFeishu: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStopMonitoring: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "打卡条件", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "到点并连接 WiFi「${WifiNetworkHelper.TARGET_SSID}」后，先显示过渡页，再打开飞书，约 5 秒后返回助手。未连接时等待 WiFi；锁屏时提醒并等待你手动解锁。手动打开飞书不会自动返回。",
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (exactAlarmAllowed) "准时提醒：已授权"
                                else "准时提醒：未授权，提醒可能延迟",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = if (overlayAllowed) "悬浮窗：已授权；仍需在手机设置中允许后台弹出界面、自启动"
                                else "悬浮窗：未授权，后台自动切换可能受限",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!notificationsAllowed) {
                            Text("通知未开启，无法显示打卡提醒", color = MaterialTheme.colorScheme.error)
                        }
                        if (!exactAlarmAllowed) {
                            OutlinedButton(onClick = onRequestExactAlarm) { Text("授权闹钟和提醒") }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = weekendEnabled,
                            role = Role.Switch,
                            onValueChange = onWeekendEnabledChange
                        )
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            text = stringResource(R.string.weekend_enabled_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(
                                if (weekendEnabled) R.string.weekend_enabled_summary
                                else R.string.weekend_disabled_summary
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = weekendEnabled, onCheckedChange = null)
                }

                Text(
                    text = stringResource(
                        if (weekendEnabled) R.string.alarm_times_every_day
                        else R.string.alarm_times_weekdays
                    ),
                    fontSize = 16.sp,
                    color = Color.Gray
                )
            }
            items(alarmTimes) { time ->
                AlarmTimeItem(time = time, onDelete = { onDeleteTime(time) })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onOpenFeishu,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3370FF))
        ) {
            Text("立即打开飞书", color = Color.White, fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isWifiMonitoringActive) {
            OutlinedButton(
                onClick = onStopMonitoring,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("停止 WiFi 监测")
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("检查通知、定位、WiFi 与悬浮窗权限")
        }
    }
}

@Composable
fun AlarmTimeItem(time: String, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = time, fontSize = 20.sp, style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color.Red)
            }
        }
    }
}
