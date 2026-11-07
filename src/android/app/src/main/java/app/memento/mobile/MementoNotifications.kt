package app.memento.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object MementoNotifications {
    private const val CHANNEL = "meeting-events"

    fun recordingStarted(ctx: Context, name: String) {
        if (MementoApp.prefs.get("notif_started") == "0") return
        show(ctx, 101, "Запись началась", name)
    }

    fun recordingStopped(ctx: Context, name: String) {
        if (MementoApp.prefs.get("notif_stopped") == "0") return
        show(ctx, 102, "Запись завершена", name)
    }

    private fun show(ctx: Context, id: Int, title: String, text: String) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Начало и завершение записи", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = PendingIntent.getActivity(
            ctx,
            id,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            id,
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setContentTitle(title)
                .setContentText(text.ifBlank { "Memento" })
                .setAutoCancel(true)
                .setContentIntent(intent)
                .build()
        )
    }
}
