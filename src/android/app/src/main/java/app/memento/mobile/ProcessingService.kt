package app.memento.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Keeps ASR and speaker diarization alive when the activity leaves the foreground. */
class ProcessingService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Обработка встреч", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Расшифровка и определение говорящих" })
            manager.createNotificationChannel(NotificationChannel(
                READY_CHANNEL, "Готовые встречи", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Завершение импорта и обработки встреч" })
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Memento обрабатывает встречу")
            .setContentText("Расшифровка и определение говорящих…")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROGRESS) {
            val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            val stage = intent.getStringExtra(EXTRA_STAGE).orEmpty()
            val progress = intent.getDoubleExtra(EXTRA_PROGRESS, 0.0).coerceIn(0.0, 1.0)
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(stageLabel(stage))
                    .setContentText(title.take(80))
                    .setContentIntent(open)
                    .setProgress(1000, (progress * 1000).toInt(), false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build())
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "memento_processing"
        private const val READY_CHANNEL = "memento_ready"
        private const val NOTIFICATION_ID = 3902
        private const val ACTION_PROGRESS = "app.memento.mobile.PROCESSING_PROGRESS"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_STAGE = "stage"
        private const val EXTRA_PROGRESS = "progress"
        private var lastProgressStage = ""
        private var lastProgressPercent = -1
        private var lastProgressAt = 0L

        private fun stageLabel(stage: String): String = when (stage) {
            "copying" -> "Memento подготавливает файл"
            "decoding" -> "Memento обрабатывает аудио"
            "transcribing" -> "Memento расшифровывает встречу"
            "diarizing" -> "Memento определяет участников"
            "summarizing" -> "Memento создаёт саммари"
            else -> "Memento обрабатывает встречу"
        }

        @Synchronized
        fun updateProgress(context: Context, title: String, stage: String, progress: Double) {
            val percent = (progress.coerceIn(0.0, 1.0) * 100).toInt()
            val now = SystemClock.elapsedRealtime()
            val stageChanged = stage != lastProgressStage
            val terminal = stage == "complete" || percent >= 100
            if (!stageChanged && !terminal && (percent == lastProgressPercent || now - lastProgressAt < 750L)) return
            lastProgressStage = stage
            lastProgressPercent = percent
            lastProgressAt = now
            val intent = Intent(context, ProcessingService::class.java).apply {
                action = ACTION_PROGRESS
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_STAGE, stage)
                putExtra(EXTRA_PROGRESS, progress)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        private fun completionIntent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
            context, requestCode, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun notifyMeetingReady(context: Context, meetingId: String, title: String) {
            val id = 4200 + (meetingId.hashCode() and 0x3ff)
            context.getSystemService(NotificationManager::class.java).notify(id,
                NotificationCompat.Builder(context, READY_CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Загруженная встреча готова")
                    .setContentText(title.take(100))
                    .setContentIntent(completionIntent(context, id))
                    .setAutoCancel(true)
                    .build())
        }

        fun notifyImportFailed(context: Context, title: String) {
            val id = 5300 + (title.hashCode() and 0x3ff)
            context.getSystemService(NotificationManager::class.java).notify(id,
                NotificationCompat.Builder(context, READY_CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Не удалось обработать аудиофайл")
                    .setContentText(title.take(100))
                    .setContentIntent(completionIntent(context, id))
                    .setAutoCancel(true)
                    .build())
        }
    }
}
