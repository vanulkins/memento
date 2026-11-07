package app.memento.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RecordingService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra("name") ?: "Memento"
        val recording = intent?.getBooleanExtra("recording", false) == true
        val wantsProjection = intent?.getBooleanExtra("projection", false) == true

        foreground(name, recording, wantsProjection)

        if (wantsProjection && MementoApp.projection == null) {
            val code = intent?.getIntExtra("result_code", 0) ?: 0
            val data = intent?.let { readIntent(it, "result_data") }
            if (code != 0 && data != null) {
                try {
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    MementoApp.projection = manager.getMediaProjection(code, data)
                } catch (e: Throwable) {
                    android.util.Log.e("MEMENTO", "getMediaProjection failed", e)
                }
            }
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readIntent(source: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) source.getParcelableExtra(key, Intent::class.java)
        else source.getParcelableExtra(key)

    private fun foreground(name: String, recording: Boolean, projection: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("rec", "Запись встречи", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            recording -> "Идёт запись: " + name
            projection -> "Системный звук подключён"
            else -> "Memento"
        }
        val n: Notification = NotificationCompat.Builder(this, "rec")
            .setContentTitle("Memento")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = 0
            if (recording) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (projection && Build.VERSION.SDK_INT >= 30) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(7, n, type)
        } else {
            startForeground(7, n)
        }
    }

    companion object {
        fun startProjection(ctx: Context, resultCode: Int, data: Intent, name: String) {
            val i = Intent(ctx, RecordingService::class.java)
                .putExtra("name", name)
                .putExtra("projection", true)
                .putExtra("result_code", resultCode)
                .putExtra("result_data", data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun start(ctx: Context, id: String, name: String, projection: Boolean) {
            val i = Intent(ctx, RecordingService::class.java)
                .putExtra("id", id)
                .putExtra("name", name)
                .putExtra("recording", true)
                .putExtra("projection", projection)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, RecordingService::class.java))
    }
}
