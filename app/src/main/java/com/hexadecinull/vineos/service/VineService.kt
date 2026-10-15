package com.hexadecinull.vineos.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hexadecinull.vineos.MainActivity
import com.hexadecinull.vineos.VineApplication.Companion.CHANNEL_VM_RUNNING
import com.hexadecinull.vineos.domain.VineVMManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class VineService : Service() {
    @Inject lateinit var vmManager: VineVMManager

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var runningCountJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = when (intent?.action ?: ACTION_START) {
        ACTION_START -> {
            startForeground(NOTIF_ID, buildNotification(vmManager.runningInstances.value.size))
            observeRunningCount()
            START_STICKY
        }
        ACTION_STOP -> {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            START_NOT_STICKY
        }
        else -> START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // The notification used to be posted once with a hardcoded count of zero, so it said
    // "No instances running" for as long as the service lived
    private fun observeRunningCount() {
        if (runningCountJob?.isActive == true) return
        runningCountJob = scope.launch {
            vmManager.runningInstances.collect { instances ->
                startForeground(NOTIF_ID, buildNotification(instances.size))
            }
        }
    }

    private fun buildNotification(runningCount: Int): Notification {
        val contentText = when (runningCount) {
            0 -> "No instances running"
            1 -> "1 instance running"
            else -> "$runningCount instances running"
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_VM_RUNNING)
            .setContentTitle("VineOS")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.hexadecinull.vineos.START_SERVICE"
        const val ACTION_STOP = "com.hexadecinull.vineos.STOP_SERVICE"
        private const val NOTIF_ID = 1001
    }
}
