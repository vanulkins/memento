package app.memento.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Meeting recorder: microphone + optional system audio (MediaProjection), written as 16 kHz PCM WAV. */
class Recorder(private val ctx: Context) {

    private val capture = AudioCapture(ctx)

    @Volatile var meetingId: String? = null
        private set
    @Volatile var sessionId: Long = 0
        private set
    @Volatile var meetingName: String = ""
        private set

    @Volatile private var started = 0L
    @Volatile private var filePath: String = ""

    val recordingFolder: File
        get() {
            val d = File(ctx.getExternalFilesDir(null), "memento-recordings")
            d.mkdirs()
            return d
        }

    fun recordingFolderLabel(): String =
        MementoApp.prefs.get("rec_folder_label")?.takeIf { it.isNotBlank() }
            ?: recordingFolder.absolutePath

    fun audioDevices(): org.json.JSONArray {
        // Android exposes several low-level routes with the same product name (and
        // sometimes a null name). The desktop-style picker needs logical sources,
        // while AudioRecord itself follows the active system route.
        // The settings component contributes its own single "По умолчанию" row.
        return org.json.JSONArray()
            .put(JSONObject().put("name", "Микрофон телефона").put("device_type", "Input")
                .put("device_id", "phone-microphone").put("is_default", false))
            .put(JSONObject().put("name", "Системный звук телефона").put("device_type", "Output")
                .put("device_id", "phone-system-audio").put("is_default", false))
    }

    fun audioPathFor(id: String): File? =
        recordingFolder.listFiles()?.firstOrNull { it.name.startsWith("memento-" + id) }

    fun permissions(): JSONObject {
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val system = capture.hasSystem() || MementoApp.projection != null
        val micStatus = if (mic) "authorized" else "denied"
        val systemStatus = if (system) "authorized" else "not_determined"
        return JSONObject()
            .put("microphone", micStatus)
            .put("systemAudio", systemStatus)
            .put("screenRecording", systemStatus)
            .put("system_audio", systemStatus)
    }

    fun start(id: String, name: String): JSONObject {
        if (meetingId != null) throw IllegalStateException("recording in progress")
        AppLog.info("audio::recording_commands", "Starting recording with default Android audio routes")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(recordingFolder, "memento-" + id + "-" + stamp + ".wav")
        RecordingService.start(ctx, id, name, MementoApp.projection != null)
        if (!capture.start(file, MementoApp.projection)) {
            throw IllegalStateException("Не удалось начать запись: нет доступа к микрофону")
        }
        meetingId = id
        meetingName = name
        sessionId = System.currentTimeMillis()
        started = System.currentTimeMillis()
        AppLog.info("audio::recording_commands", "Recording started successfully")
        filePath = file.absolutePath
        MementoNotifications.recordingStarted(ctx, name)
        return JSONObject()
            .put("meeting_id", id)
            .put("session_id", sessionId)
            .put("path", file.absolutePath)
            .put("started_at", started)
    }

    fun stop(): JSONObject {
        if (meetingId == null) {
            return JSONObject()
                .put("status", "success")
                .put("meeting_id", "")
                .put("duration_seconds", 0)
                .put("path", "")
                .put("call_api", false)
        }
        AppLog.info("audio::recording_commands", "Stopping audio capture")
        capture.stop()
        try { MementoApp.projection?.stop() } catch (e: Throwable) { }
        MementoApp.projection = null
        val dur = capture.seconds()
        val path = filePath
        val id = meetingId ?: ""
        meetingId = null
        started = 0
        filePath = ""
        RecordingService.stop(ctx)
        MementoNotifications.recordingStopped(ctx, meetingName)
        val savedUri = if (MementoApp.prefs.get("rec_auto_save") != "0" && path.isNotBlank()) {
            copyToSelectedFolder(File(path))
        } else null
        AppLog.info("audio::recording_commands", "Recording session finalized successfully")
        return JSONObject()
            .put("status", "success")
            .put("meeting_id", id)
            .put("session_id", sessionId)
            .put("duration_seconds", dur)
            .put("path", path)
            .put("audio_path", path)
            .put("saved_uri", savedUri ?: JSONObject.NULL)
            .put("auto_save", MementoApp.prefs.get("rec_auto_save") != "0")
            .put("call_api", true)
    }

    fun pause(): Boolean = capture.pause()

    fun resume(): Boolean = capture.resume()

    fun paused(): Boolean = capture.isPaused()

    fun level(): Double = capture.currentLevel()

    fun recordingDuration(): Double =
        if (started == 0L) 0.0 else (System.currentTimeMillis() - started) / 1000.0

    fun activeDuration(): Double = capture.seconds()

    fun currentPath(): String = filePath

    fun state(): JSONObject = JSONObject()
        .put("status", if (!active()) "idle" else if (paused()) "paused" else "recording")
        .put("is_recording", active())
        .put("is_paused", paused())
        .put("is_active", active())
        .put("recording_duration", recordingDuration())
        .put("active_duration", activeDuration())
        .put("session_id", if (sessionId == 0L) JSONObject.NULL else sessionId)

    fun active() = meetingId != null

    fun name(n: String) {
        meetingName = n
    }

    private fun copyToSelectedFolder(source: File): String? {
        if (!source.isFile) return null
        val tree = MementoApp.prefs.get("rec_folder_uri")?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val treeUri = Uri.parse(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val target = DocumentsContract.createDocument(ctx.contentResolver, parent, "audio/wav", source.name)
                ?: return null
            ctx.contentResolver.openOutputStream(target, "w")?.use { out ->
                source.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
            } ?: return null
            target.toString()
        } catch (e: Throwable) {
            android.util.Log.e("MEMENTO", "copy recording to selected folder failed", e)
            null
        }
    }
}
