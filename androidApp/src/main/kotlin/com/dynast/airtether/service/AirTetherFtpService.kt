package com.dynast.airtether.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dynast.airtether.MainActivity
import com.dynast.airtether.domain.port.AndroidStorageContextHolder
import com.dynast.airtether.ingest.AirTetherRuntime
import com.dynast.airtether.ui.util.resolveLocalIpAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AirTetherFtpService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AndroidStorageContextHolder.appContext = applicationContext

        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                AirTetherRuntime.ftpIngestService.stopFtpServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 2121) ?: 2121
        ensureNotificationChannel()
        val notification = buildNotification(port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            AirTetherRuntime.ftpIngestService.startFtpServer(port)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirTether FTP Ingestion",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps FTP Port 2121 active for wireless camera tethering"
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AirTetherFtpService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ip = resolveLocalIpAddress()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("AirTether FTP Listening")
            .setContentText("ftp://$ip:$port")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "airtether_ftp_channel"
        private const val NOTIFICATION_ID = 2121
        private const val ACTION_START = "com.dynast.airtether.action.START_FTP"
        private const val ACTION_STOP = "com.dynast.airtether.action.STOP_FTP"
        private const val EXTRA_PORT = "extra_ftp_port"

        fun start(context: Context, port: Int = 2121) {
            val intent = Intent(context, AirTetherFtpService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AirTetherFtpService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
