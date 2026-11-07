package app.memento.mobile

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.Intent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Native command dispatcher. Exposes the same Tauri command surface (130 commands) that the
 * original desktop frontend invokes. Real logic: SQLite (Db), recorder, model downloads, prefs.
 * Unknown-but-inert read commands return honest empty values; state-mutating ML pipeline commands
 * (transcribe/summarize/RAG) are reported as not-yet-available rather than faked.
 */
class Bridge(private val act: MainActivity) {

    private fun ok(v: Any?) = JSONObject().put("value", v ?: JSONObject.NULL)
    private fun err(m: String) = JSONObject().put("__mementoError", m)
    private fun identityJson() = JSONObject()
        .put("display_name", MementoApp.prefs.get("user_name") ?: "")
        .put("email", "")
    private val finalizations = HashMap<Long, JSONObject>()
    val app get() = MementoApp.app

    private fun processingKey(id: String) = "processing_job:$id"
    private fun summaryKey(id: String) = "summary_job:$id"
    private fun importKey(id: String) = "import_job:$id"

    private fun automaticSummary(id: String, replaceTitle: Boolean, onProgress: ((Double) -> Unit)? = null): Boolean {
        if (id.isBlank() || MementoApp.db.isFixtureMeeting(id) || MementoApp.prefs.get("summarization_enabled") == "0") {
            MementoApp.prefs.delete(summaryKey(id))
            return false
        }
        if (MementoApp.db.summary(id) != null) {
            MementoApp.prefs.delete(summaryKey(id))
            return true
        }
        MementoApp.db.ensureSpeakerAssignments(id)
        val context = MementoApp.db.aiContext(id)
        if (context.isBlank()) return false
        val prior = runCatching { JSONObject(MementoApp.prefs.get(summaryKey(id)).orEmpty()) }.getOrNull()
        val saved = JSONObject().put("meeting_id", id).put("replace_title", replaceTitle)
            .put("saved_at", System.currentTimeMillis())
            .put("attempts", (prior?.optInt("attempts", 0) ?: 0) + 1)
            .put("next_retry_at", 0L)
        MementoApp.prefs.setSync(summaryKey(id), saved.toString())
        repeat(2) { attempt ->
            try {
                onProgress?.invoke(0.985 + attempt * 0.005)
                MementoApp.summary.process(
                    JSONObject().put("meetingId", id).put("text", context)
                        .put("templateId", "standard_meeting").put("summaryLanguage", "auto")
                        .put("replaceGeneratedTitle", replaceTitle),
                    JSONObject().put("provider", "builtin-ai").put("model", "deepseek-v4-pro"),
                    JSONObject()
                )
                MementoApp.prefs.delete(summaryKey(id))
                fire("summary-complete", JSONObject().put("meeting_id", id).put("meetingId", id))
                fire("meetings-updated", JSONObject().put("meeting_id", id).put("meetingId", id))
                return true
            } catch (e: Throwable) {
                AppLog.error("summary", "Automatic summary attempt ${attempt + 1} failed: ${e.message}")
                if (attempt == 0) try { Thread.sleep(1500) } catch (_: InterruptedException) { return false }
            }
        }
        saved.put("next_retry_at", System.currentTimeMillis() + 30_000L)
        MementoApp.prefs.setSync(summaryKey(id), saved.toString())
        return false
    }

    private fun scheduleAutomaticSummary(id: String, replaceTitle: Boolean = false) {
        if (id.isBlank() || MementoApp.db.summary(id) != null || MementoApp.db.transcriptCount(id) == 0) return
        if (!activeProcessing.add(id)) return
        ContextCompat.startForegroundService(app, Intent(app, ProcessingService::class.java))
        Thread {
            try {
                fire("retranscription-progress", JSONObject().put("meeting_id", id).put("meetingId", id)
                    .put("stage", "exporting").put("progress", 0.98))
                automaticSummary(id, replaceTitle)
            } finally {
                activeProcessing.remove(id)
                if (activeProcessing.isEmpty()) app.stopService(Intent(app, ProcessingService::class.java))
            }
        }.apply { name = "meeting-summary-$id"; isDaemon = true; start() }
    }

    private fun persistRetranscriptionJob(id: String, audio: File, deleteAfter: Boolean) {
        val job = JSONObject().put("meeting_id", id).put("audio_path", audio.absolutePath)
            .put("delete_after", deleteAfter).put("saved_at", System.currentTimeMillis())
        if (!MementoApp.prefs.setSync(processingKey(id), job.toString())) {
            throw IllegalStateException("Не удалось сохранить задание обработки")
        }
        MementoApp.db.setMeetingStatus(id, "processing")
    }

    fun resumePendingProcessing() {
        MementoApp.prefs.keys().filter { it.startsWith("import_job:") }.forEach { key ->
            val job = runCatching { JSONObject(MementoApp.prefs.get(key).orEmpty()) }.getOrNull()
            val id = job?.optString("meeting_id").orEmpty()
            val source = File(job?.optString("source_path").orEmpty())
            val title = job?.optString("title").orEmpty().ifBlank { source.nameWithoutExtension }
            val runId = job?.optString("run_id").orEmpty().ifBlank { UUID.randomUUID().toString() }
            if (id.isNotBlank() && source.isFile && !activeProcessing.contains(id)) {
                Thread { processImport(source, title, id, runId) }.apply {
                    name = "resumed-import-$id"
                    isDaemon = true
                    start()
                }
            } else if (id.isBlank() || !source.isFile) {
                MementoApp.prefs.delete(key)
                if (id.isNotBlank() && MementoApp.db.transcriptCount(id) == 0) {
                    runCatching { MementoApp.db.deleteMeeting(id) }
                }
            }
        }
        MementoApp.prefs.keys().filter { it.startsWith("processing_job:") }.forEach { key ->
            val job = runCatching { JSONObject(MementoApp.prefs.get(key).orEmpty()) }.getOrNull() ?: return@forEach
            val id = job.optString("meeting_id")
            val audio = File(job.optString("audio_path"))
            if (id.isNotBlank() && audio.isFile) {
                launchRetranscription(id, audio, job.optBoolean("delete_after", false), resumed = true)
            } else {
                MementoApp.prefs.delete(key)
            }
        }
        MementoApp.prefs.keys().filter { it.startsWith("summary_job:") }.forEach { key ->
            val job = runCatching { JSONObject(MementoApp.prefs.get(key).orEmpty()) }.getOrNull() ?: return@forEach
            val id = job.optString("meeting_id")
            if (id.isNotBlank() && MementoApp.db.transcriptCount(id) > 0 &&
                job.optLong("next_retry_at", 0L) <= System.currentTimeMillis()) {
                scheduleAutomaticSummary(id, job.optBoolean("replace_title", false))
            } else if (id.isBlank() || MementoApp.db.transcriptCount(id) == 0) MementoApp.prefs.delete(key)
        }
    }

    private fun launchRetranscription(id: String, audio: File, deleteAfter: Boolean, resumed: Boolean = false) {
        if (!activeProcessing.add(id)) return
        try {
            persistRetranscriptionJob(id, audio, deleteAfter)
        } catch (e: Throwable) {
            activeProcessing.remove(id)
            throw e
        }
        ContextCompat.startForegroundService(app, Intent(app, ProcessingService::class.java))
        MementoApp.asr.cancelFlag.set(false)
        Thread {
            var transcriptSaved = false
            var processingFinished = false
            try {
                fire("retranscription-progress", JSONObject().put("meeting_id", id).put("meetingId", id).put("stage", "loading").put("progress", 0.0))
                val res = MementoApp.asr.transcribe(audio) { p ->
                    fire("retranscription-progress", JSONObject().put("meeting_id", id).put("meetingId", id).put("stage", "transcribing").put("progress", p))
                }
                val segments = res.segments.map { Triple(it.start, it.end, it.text) }
                MementoApp.db.replaceTranscripts(id, segments)
                transcriptSaved = true
                val complete = JSONObject().put("meeting_id", id).put("meetingId", id)
                    .put("transcript", res.text).put("segments_count", res.segments.size)
                    .put("duration_seconds", audio.length() / 32000.0).put("resumed", resumed)
                if (res.segments.isEmpty()) {
                    fire("retranscription-error", JSONObject().put("meeting_id", id).put("meetingId", id)
                        .put("error", "Речь не распознана: проверьте громкость записи и загруженную модель"))
                } else {
                    if (MementoApp.diarization.ready()) runCatching {
                        val turns = MementoApp.diarization.diarize(audio) { p ->
                            fire("retranscription-progress", JSONObject().put("meeting_id", id).put("meetingId", id)
                                .put("stage", "diarizing").put("progress", p))
                        }
                        MementoApp.db.applyDiarization(id, turns.map { Triple(it.start, it.end, it.speaker) })
                        fire("diarization-complete", JSONObject().put("meeting_id", id).put("meetingId", id)
                            .put("segments", MementoApp.db.assignedCount(id)))
                    }.onFailure {
                        AppLog.error("diarization", "Automatic diarization failed: ${it.message}")
                    }
                    MementoApp.db.ensureSpeakerAssignments(id)
                    fire("retranscription-progress", JSONObject().put("meeting_id", id).put("meetingId", id)
                        .put("stage", "exporting").put("progress", 0.98))
                    automaticSummary(id, replaceTitle = false)
                }
                // The meeting screen treats this event as the end of the whole pipeline and
                // reloads transcript/summary state. It must be emitted after diarization and
                // automatic summarization, otherwise the screen permanently captures the
                // temporary "summary unavailable" document.
                fire("retranscription-complete", complete)
                processingFinished = true
            } catch (e: InterruptedException) {
                fire("retranscription-error", JSONObject().put("meeting_id", id).put("meetingId", id).put("error", "Транскрипция отменена"))
            } catch (e: Throwable) {
                fire(if (transcriptSaved) "diarization-error" else "retranscription-error", JSONObject()
                    .put("meeting_id", id).put("meetingId", id).put("error", e.message ?: e.javaClass.simpleName))
                // A completed transcript must not be replaced on every launch if diarization is unavailable.
                processingFinished = transcriptSaved
            } finally {
                if (processingFinished) {
                    MementoApp.db.setMeetingStatus(id, "done")
                    MementoApp.prefs.delete(processingKey(id))
                    if (deleteAfter) {
                        runCatching { audio.delete() }
                        MementoApp.db.clearAudioPath(id)
                    }
                }
                activeProcessing.remove(id)
                if (activeProcessing.isEmpty()) app.stopService(Intent(app, ProcessingService::class.java))
            }
        }.apply { name = "meeting-processing-$id"; start() }
    }

    fun handle(raw: String): JSONObject? {
        val o = try { JSONObject(raw) } catch (e: Exception) { return null }
        val cmd = o.optString("cmd")
        val cb = o.optString("callbackId")
        if (cmd == "__event|listen" || cmd == "__event|unlisten" || cmd == "__emit") return null
        AppLog.info("api::commands", "$cmd called (native)")
        if (cmd == "select_and_validate_audio_command") {
            act.pickAudio(cb)
            return null
        }
        if (cmd == "select_and_validate_audio_folder_command") {
            act.pickAudioBatch(cb)
            return null
        }
        if (cmd == "select_recording_folder") {
            act.pickRecordingFolder(cb)
            return null
        }
        if (cmd == "trigger_system_audio_permission_command") {
            act.requestSystemAudio(cb)
            return null
        }
        if (cmd == "trigger_microphone_permission") {
            act.requestMic(cb)
            return null
        }
        if (cmd == "submit_feedback") {
            submitFeedback(o.optJSONObject("args") ?: JSONObject(), cb)
            return null
        }
        if (cmd == "api_process_transcript" || cmd == "rag_ask") {
            val args = o.optJSONObject("args") ?: JSONObject()
            Thread {
                val result = try { dispatch(cmd, args) } catch (e: Throwable) {
                    AppLog.error("api::commands", "$cmd failed: ${e.message ?: e.javaClass.simpleName}")
                    err(e.message ?: "Ошибка суммаризации")
                }
                if (cb.isNotBlank() && cb != "__none") act.reply(cb, result)
            }.apply { isDaemon = true }.start()
            return null
        }
        val args = o.optJSONObject("args") ?: JSONObject()
        val result = try { dispatch(cmd, args) } catch (e: Throwable) {
            AppLog.error("api::commands", "$cmd failed: ${e.message ?: e.javaClass.simpleName}")
            err(e.message ?: "error")
        }
        if (cb.isNotEmpty() && cb != "__none") act.reply(cb, result)
        return result
    }

    fun fire(event: String, payload: JSONObject) = act.fireEvent(event, payload)

    private fun startRecording(id: String, name: String): JSONObject {
        val res = MementoApp.recorder.start(id, name)
        MementoApp.db.createMeeting(id, name, res.optString("path"))
        fire("recording-started", JSONObject()
            .put("meeting_id", id)
            .put("session_id", res.opt("session_id"))
            .put("meeting_name", name))
        return ok(res)
    }

    internal fun sourceFile(args: JSONObject): File? {
        val nested = args.optJSONObject("item") ?: args.optJSONObject("file")
        val candidates = arrayOf("sourcePath", "source_path", "path", "filePath", "audioPath", "audio_path")
        for (key in candidates) {
            val raw = args.optString(key).ifBlank { nested?.optString(key).orEmpty() }
            if (raw.isNotBlank()) return File(raw)
        }
        return null
    }

    internal fun durableImportSource(source: File): File {
        require(source.isFile) { "Файл не найден: ${source.absolutePath}" }
        val dir = File(app.filesDir, "import-jobs").apply { mkdirs() }
        val ext = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        val target = File(dir, "${UUID.randomUUID()}$ext")
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output, 1024 * 1024) } }
        require(target.isFile && target.length() == source.length()) { "Не удалось подготовить аудиофайл к импорту" }
        if (source.parentFile == File(app.filesDir, "imports")) runCatching { source.delete() }
        return target
    }

    private fun publishImportProgress(runId: String, id: String, title: String, stage: String, progress: Double) {
        val value = progress.coerceIn(0.0, 1.0)
        fire("import-progress", JSONObject().put("run_id", runId).put("meeting_id", id)
            .put("stage", stage).put("progress", value))
        act.showImportProgress(title, stage, value)
        ProcessingService.updateProgress(app, title, stage, value)
    }

    private fun publishImportReady(id: String, title: String) {
        if (MainActivity.isAppForeground()) act.showImportReady(title)
        else ProcessingService.notifyMeetingReady(app, id, title)
    }

    private fun publishImportFailure(title: String, message: String) {
        AppLog.error("import", "$title: $message")
        if (MainActivity.isAppForeground()) act.showImportFailure(title)
        else ProcessingService.notifyImportFailed(app, title)
    }

    private fun importSingle(args: JSONObject): JSONObject {
        val picked = sourceFile(args) ?: return err("Не передан путь к аудиофайлу")
        if (!picked.isFile) return err("Файл не найден: ${picked.absolutePath}")
        val source = try { durableImportSource(picked) } catch (e: Throwable) {
            return err(e.message ?: "Не удалось открыть аудиофайл")
        }
        val title = args.optString("title", picked.nameWithoutExtension).ifBlank { picked.nameWithoutExtension }
        val runId = UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        val job = JSONObject().put("meeting_id", id).put("source_path", source.absolutePath)
            .put("title", title).put("run_id", runId).put("saved_at", System.currentTimeMillis())
        if (!MementoApp.prefs.setSync(importKey(id), job.toString())) {
            source.delete()
            return err("Не удалось сохранить задание импорта")
        }
        Thread { processImport(source, title, id, runId) }.apply { isDaemon = true }.start()
        return ok(JSONObject().put("run_id", runId).put("meeting_id", id))
    }

    private fun importBatch(args: JSONObject): JSONObject {
        val items = args.optJSONArray("items") ?: JSONArray()
        val runId = UUID.randomUUID().toString()
        val ids = JSONArray()
        val queue = ArrayList<Triple<File, String, String>>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val picked = sourceFile(item) ?: continue
            if (!picked.isFile) continue
            val source = runCatching { durableImportSource(picked) }.getOrNull() ?: continue
            val title = item.optString("title", picked.nameWithoutExtension).ifBlank { picked.nameWithoutExtension }
            val id = UUID.randomUUID().toString()
            val job = JSONObject().put("meeting_id", id).put("source_path", source.absolutePath)
                .put("title", title).put("run_id", runId).put("saved_at", System.currentTimeMillis())
            if (!MementoApp.prefs.setSync(importKey(id), job.toString())) {
                source.delete()
                continue
            }
            ids.put(id)
            queue.add(Triple(source, title, id))
        }
        if (queue.isEmpty()) return err("Не выбраны доступные аудиофайлы")
        Thread {
            queue.forEachIndexed { i, (source, title, id) ->
                processImport(source, title, id, runId, emitSingleComplete = false)
                fire("batch-import-progress", JSONObject().put("run_id", runId).put("completed", i + 1).put("total", queue.size))
            }
            fire("batch-import-complete", JSONObject().put("run_id", runId).put("meeting_ids", ids))
        }.apply { isDaemon = true }.start()
        return ok(JSONObject().put("run_id", runId).put("meeting_ids", ids))
    }

    private fun processImport(source: File, title: String, id: String, runId: String, emitSingleComplete: Boolean = true) {
        val target = File(MementoApp.recorder.recordingFolder, "memento-$id.wav")
        activeProcessing.add(id)
        ContextCompat.startForegroundService(app, Intent(app, ProcessingService::class.java))
        try {
            // If Android killed the process after local transcription but while the cloud model
            // was answering, resume from the durable transcript instead of decoding and running
            // both local models a second time. This is especially important for longer imports.
            if (MementoApp.db.transcriptCount(id) > 0) {
                MementoApp.db.setMeetingStatus(id, "processing")
                MementoApp.db.ensureSpeakerAssignments(id)
                publishImportProgress(runId, id, title, "summarizing", 0.97)
                automaticSummary(id, replaceTitle = true) { p ->
                    publishImportProgress(runId, id, title, "summarizing", p)
                }
                MementoApp.db.setMeetingStatus(id, "done")
                val complete = JSONObject().put("run_id", runId).put("meeting_id", id)
                    .put("segments_count", MementoApp.db.transcriptCount(id)).put("resumed", true)
                if (emitSingleComplete) fire("import-complete", complete)
                fire("meetings-updated", JSONObject().put("meeting_id", id).put("meetingId", id))
                publishImportProgress(runId, id, title, "complete", 1.0)
                publishImportReady(id, title)
                return
            }
            publishImportProgress(runId, id, title, "copying", 0.02)
            MementoApp.db.createMeeting(id, title, target.absolutePath)
            val info = AudioDecoder.normalize(source, target) { progress ->
                publishImportProgress(runId, id, title, "decoding", 0.05 + progress * 0.22)
            }
            MementoApp.db.saveMeeting(id, title, target.absolutePath, info.durationSeconds, "")
            MementoApp.db.setMeetingStatus(id, "processing")
            if (!MementoApp.asr.ready()) throw IllegalStateException("Выбранная модель расшифровки не загружена. Скачайте её в настройках.")
            val transcript = MementoApp.asr.transcribe(target) { progress ->
                publishImportProgress(runId, id, title, "transcribing", 0.27 + progress * 0.60)
            }
            if (transcript.segments.isEmpty()) throw IllegalStateException("В аудиофайле не удалось распознать речь")
            MementoApp.db.replaceTranscripts(id, transcript.segments.map { Triple(it.start, it.end, it.text) })
            if (transcript.segments.isNotEmpty() && MementoApp.diarization.ready()) {
                publishImportProgress(runId, id, title, "diarizing", 0.88)
                runCatching {
                    val turns = MementoApp.diarization.diarize(target) { p ->
                        publishImportProgress(runId, id, title, "diarizing", 0.88 + p * 0.07)
                    }
                    MementoApp.db.applyDiarization(id, turns.map { Triple(it.start, it.end, it.speaker) })
                }.onFailure { AppLog.error("diarization", "Import diarization failed: ${it.message}") }
            }
            MementoApp.db.ensureSpeakerAssignments(id)
            publishImportProgress(runId, id, title, "summarizing", 0.97)
            automaticSummary(id, replaceTitle = true) { p ->
                publishImportProgress(runId, id, title, "summarizing", p)
            }
            MementoApp.db.setMeetingStatus(id, "done")
            val complete = JSONObject().put("run_id", runId).put("meeting_id", id)
                .put("duration_seconds", info.durationSeconds).put("segments_count", transcript.segments.size)
            if (emitSingleComplete) fire("import-complete", complete)
            fire("meetings-updated", JSONObject().put("meeting_id", id).put("meetingId", id))
            publishImportProgress(runId, id, title, "complete", 1.0)
            publishImportReady(id, title)
        } catch (e: Throwable) {
            val message = e.message ?: "Не удалось импортировать аудио"
            fire(if (emitSingleComplete) "import-error" else "batch-import-error", JSONObject()
                .put("run_id", runId).put("meeting_id", id).put("stage", "processing").put("error", message))
            publishImportFailure(title, message)
            if (MementoApp.db.transcriptCount(id) == 0) {
                runCatching { MementoApp.db.deleteMeeting(id) }
                runCatching { target.delete() }
                fire("meetings-updated", JSONObject().put("meeting_id", id).put("meetingId", id))
            } else {
                // A cloud summary failure must not strand an otherwise usable local meeting.
                // api_get_summary keeps the persisted summary retry alive independently.
                MementoApp.db.setMeetingStatus(id, "done")
            }
        } finally {
            MementoApp.prefs.delete(importKey(id))
            if (source.parentFile == File(app.filesDir, "import-jobs")) source.delete()
            activeProcessing.remove(id)
            if (activeProcessing.isEmpty()) app.stopService(Intent(app, ProcessingService::class.java))
        }
    }

    private fun dispatch(cmd: String, args: JSONObject): JSONObject = when (cmd) {

        // ---------- meetings list / metadata (real SQLite) ----------
        "api_get_meetings" -> ok(MementoApp.db.meetings())
        "api_get_meeting_metadata" -> {
            val id = args.optString("meetingId")
            val arr = MementoApp.db.meetings()
            var found: JSONObject? = null
            for (i in 0 until arr.length()) { val m = arr.getJSONObject(i); if (m.getString("id") == id) found = m }
            ok(found ?: JSONObject.NULL)
        }
        "api_delete_meeting" -> {
            val id = args.optString("meetingId")
            if (args.optBoolean("deleteRecordingFiles", false)) {
                MementoApp.db.audioPath(id)?.let { runCatching { File(it).delete() } }
            }
            MementoApp.db.deleteMeeting(id)
            ok(JSONObject().put("message", "Meeting deleted successfully").put("files_deleted", args.optBoolean("deleteRecordingFiles", false)))
        }
            "api_get_meeting_transcripts" -> {
                val id = args.optString("meetingId")
                val segs = MementoApp.db.transcriptSegments(id)
                ok(JSONObject()
                    .put("transcripts", segs)
                    .put("total_count", segs.length())
                    .put("has_more", false))
            }
        "download_transcript_markdown" -> downloadTranscriptMarkdown(args)
        "search_meetings" -> ok(MementoApp.db.searchMeetings(
            args.optString("query"), args.optJSONObject("filters"), args.optInt("limit", 50)
        ))
        "api_search_transcripts" -> ok(MementoApp.db.searchTranscripts(args.optString("query"), null, 50))

        // ---------- recording lifecycle ----------
        "is_recording" -> ok(MementoApp.recorder.active())
        "get_recording_state" -> ok(MementoApp.recorder.state())
        "get_recording_meeting_name" -> ok(MementoApp.recorder.meetingName)
        "check_audio_permissions" -> ok(MementoApp.recorder.permissions())
        "trigger_system_audio_permission_command" -> ok(true)
        "get_current_microphone_level" -> ok(MementoApp.recorder.level())
        "set_recording_meeting_name" -> { MementoApp.recorder.name(args.optString("name")); ok(JSONObject.NULL) }
        "start_recording" -> startRecording(
            args.optString("meetingId", UUID.randomUUID().toString()),
            args.optString("name", "Встреча")
        )
        "start_recording_with_devices_and_meeting" -> {
            args.optString("micDeviceName").takeIf { it.isNotBlank() }
                ?.let { MementoApp.prefs.set("rec_mic", it) }
            args.optString("systemDeviceName").takeIf { it.isNotBlank() }
                ?.let { MementoApp.prefs.set("rec_system", it) }
            startRecording(UUID.randomUUID().toString(), args.optString("meetingName", "Встреча"))
        }
        "pause_recording" -> {
            val paused = MementoApp.recorder.pause()
            if (paused) fire("recording-paused", MementoApp.recorder.state())
            ok(paused)
        }
        "resume_recording" -> {
            val resumed = MementoApp.recorder.resume()
            if (resumed) fire("recording-resumed", MementoApp.recorder.state())
            ok(resumed)
        }
        "stop_recording" -> {
            val stoppedName = MementoApp.recorder.meetingName
            val res = MementoApp.recorder.stop()
            val path = res.optString("path")
            val id = res.optString("meeting_id")
            val sessionId = res.optLong("session_id")
            if (id.isNotEmpty()) {
                val dur = res.optDouble("duration_seconds", 0.0)
                val audio = File(path)
                MementoApp.db.saveMeeting(id, stoppedName, if (audio.exists()) path else "", dur, "")
                finalizations[sessionId] = JSONObject().put("meeting_id", id).put("meeting_name", stoppedName)
                // Always hand the saved recording to the durable processing queue. The old
                // readiness guard silently dropped the only processing request when model
                // discovery had not completed yet, leaving the meeting without a transcript
                // or summary forever.
                startRetranscription(JSONObject().put("meeting_id", id).put("meetingId", id)
                    .put("meetingFolderPath", path)
                    .put("deleteAudioAfter", MementoApp.prefs.get("rec_auto_save") == "0"))
            }
            fire("recording-stopped", JSONObject()
                .put("meeting_id", id)
                .put("session_id", res.opt("session_id"))
                .put("audio_path", path)
                .put("duration_seconds", res.optDouble("duration_seconds", 0.0)))
            fire("recording-stop-complete", JSONObject()
                .put("call_api", res.opt("call_api"))
                .put("session_id", res.opt("session_id")))
            ok(res)
        }
        "provide_recording_people" -> ok(JSONObject.NULL)
        "get_recording_finalization" -> {
            val sid = args.optLong("sessionId")
            val rec = finalizations.remove(sid)
            ok(JSONObject()
                .put("meeting_id", rec?.optString("meeting_id") ?: JSONObject.NULL)
                .put("meeting_name", rec?.optString("meeting_name") ?: JSONObject.NULL)
                .put("save_error", JSONObject.NULL)
                .put("transcription_complete", true))
        }
        "start_retranscription_command" -> startRetranscription(args)
        "cancel_retranscription_command" -> {
            MementoApp.asr.cancelFlag.set(true)
            ok(JSONObject.NULL)
        }
        "diarize_meeting" -> startDiarization(args)
        "get_meeting_audio_playback_info" -> {
            val id = args.optString("meetingId")
            val stored = MementoApp.db.audioPath(id)
            val audio = if (stored.isNullOrEmpty()) null else File(stored)
            val exists = audio != null && audio.isFile
            ok(JSONObject()
                .put("meeting_id", id)
                .put("audio_path", if (exists) audio!!.absolutePath else JSONObject.NULL)
                .put("exists", exists)
                .put("duration_seconds", if (exists) audio!!.length() / 32000.0 else 0.0))
        }
        "start_import_audio_command" -> importSingle(args)
        "start_batch_import_audio_command" -> importBatch(args)
        "cancel_import_command" -> {
            MementoApp.asr.cancelFlag.set(true)
            MementoApp.diarization.cancelFlag.set(true)
            ok(JSONObject.NULL)
        }
        "validate_audio_import_path_command" -> {
            val source = File(args.optString("path"))
            if (!source.isFile) err("Файл не найден") else {
                val info = AudioDecoder.inspect(source)
                ok(JSONArray().put(JSONObject().put("sourcePath", source.absolutePath)
                    .put("title", source.nameWithoutExtension).put("size_bytes", source.length())
                    .put("duration_seconds", info.durationSeconds).put("mime_type", info.mime)))
            }
        }
        "get_transcript_history" -> ok(JSONArray())
        "get_import_activity_command" -> {
            val key = MementoApp.prefs.keys().firstOrNull { it.startsWith("import_job:") }
            val job = key?.let { runCatching { JSONObject(MementoApp.prefs.get(it).orEmpty()) }.getOrNull() }
            ok(if (job == null) JSONObject.NULL else JSONObject()
                .put("run_id", job.optString("run_id"))
                .put("meeting_id", job.optString("meeting_id"))
                .put("title", job.optString("title"))
                .put("stage", "processing")
                .put("progress_percentage", 0))
        }
        "set_recording_preferences" -> {
            val prefs = args.optJSONObject("preferences") ?: JSONObject()
            if (prefs.has("auto_save")) {
                MementoApp.prefs.set("rec_auto_save", if (prefs.optBoolean("auto_save", true)) "1" else "0")
            }
            if (prefs.has("file_format")) {
                MementoApp.prefs.set("rec_file_format", "wav")
            }
            if (prefs.has("preferred_mic_device")) {
                val value = prefs.opt("preferred_mic_device")
                if (value == null || value == JSONObject.NULL || value.toString().isBlank() || value.toString() == "null") {
                    MementoApp.prefs.delete("rec_mic")
                } else MementoApp.prefs.set("rec_mic", value.toString())
            }
            if (prefs.has("preferred_system_device")) {
                val value = prefs.opt("preferred_system_device")
                if (value == null || value == JSONObject.NULL || value.toString().isBlank() || value.toString() == "null") {
                    MementoApp.prefs.delete("rec_system")
                } else MementoApp.prefs.set("rec_system", value.toString())
            }
            if (prefs.has("save_folder") && prefs.optString("save_folder").isNotBlank()) {
                MementoApp.prefs.set("rec_folder_label", prefs.optString("save_folder"))
            }
            ok(JSONObject.NULL)
        }
        "get_recording_preferences" -> ok(JSONObject()
            .put("save_folder", MementoApp.recorder.recordingFolderLabel())
            .put("auto_save", MementoApp.prefs.get("rec_auto_save") != "0")
            .put("file_format", "wav")
            .put("preferred_mic_device", MementoApp.prefs.get("rec_mic")
                ?.takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL)
            .put("preferred_system_device", MementoApp.prefs.get("rec_system")
                ?.takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL))
        "select_recording_folder" -> ok(MementoApp.recorder.recordingFolderLabel())
        "get_default_recordings_folder_path" -> ok(MementoApp.recorder.recordingFolderLabel())
        "open_meeting_folder" -> {
            val path = MementoApp.db.audioPath(args.optString("meetingId"))
            if (!path.isNullOrBlank() && File(path).exists()) act.openFileLocation(File(path))
            ok(JSONObject.NULL)
        }
        "export_meeting_audio_mp3" -> err("MP3 export requires ffmpeg (not bundled in this build)")

        // ---------- local model downloads (real HTTP) ----------
        "gigaam_status" -> ok(MementoApp.models.statusJson())
        "gigaam_download_model" -> { MementoApp.models.download(MementoApp.models.selectedVariant()); ok(JSONObject.NULL) }
        "gigaam_select_variant" -> { MementoApp.asr.release(); MementoApp.models.select(args.optString("variant")); ok(JSONObject.NULL) }
        "builtin_ai_list_models" -> ok(MementoApp.summary.listBuiltInModels())
        "builtin_ai_delete_model", "builtin_ai_cancel_download", "builtin_ai_download_model" -> ok(JSONObject.NULL)
        "builtin_ai_is_model_ready" -> ok(args.optString("modelName") == "memento-local")
        "builtin_ai_get_model_info" -> ok(if (args.optString("modelName") == "memento-local")
            MementoApp.summary.listBuiltInModels().getJSONObject(0) else JSONObject.NULL)
        "diarization_status" -> ok(MementoApp.models.diarStatusJson())
        "download_diarization_models" -> { MementoApp.models.download(MementoApp.models.diarizationVariant()); ok(JSONObject.NULL) }
        "whisper_get_available_models" -> ok(JSONArray())
        "parakeet_get_available_models" -> ok(JSONArray())
        "whisper_get_models_directory" -> ok(MementoApp.models.root.absolutePath)
        "get_database_directory" -> ok(app.getDatabasePath("memento.db").parentFile?.absolutePath ?: app.filesDir.absolutePath)

        // ---------- settings / identity ----------
        "get_app_settings" -> ok(MementoApp.prefs.snapshot())
        "feedback_log_bundle" -> ok(act.feedbackLogBundle())
        "get_notification_settings" -> ok(JSONObject()
            .put("desktop", true)
            .put("sound", true)
            .put("notification_preferences", JSONObject()
                .put("show_recording_started", MementoApp.prefs.get("notif_started") != "0")
                .put("show_recording_stopped", MementoApp.prefs.get("notif_stopped") != "0")))
        "set_notification_settings" -> {
            val settings = args.optJSONObject("settings")
            val prefs = settings?.optJSONObject("notification_preferences")
            if (prefs != null) {
                MementoApp.prefs.set("notif_started", if (prefs.optBoolean("show_recording_started", true)) "1" else "0")
                MementoApp.prefs.set("notif_stopped", if (prefs.optBoolean("show_recording_stopped", true)) "1" else "0")
            }
            ok(JSONObject.NULL)
        }
        "set_app_setting" -> { MementoApp.prefs.set(args.optString("key"), args.optString("value")); ok(JSONObject.NULL) }
        "get_user_identity" -> ok(identityJson())
        "set_user_display_name" -> {
            MementoApp.prefs.set("user_name", args.optString("name"))
            ok(identityJson())
        }
        "detect_user_identity_from_outlook" -> ok(JSONObject()
            .put("source", "")
            .put("display_name", identityJson().optString("display_name")))
        "set_language_preference" -> { MementoApp.prefs.set("language", args.optString("language")); ok(JSONObject.NULL) }
        "get_ollama_models" -> {
            val endpoint = args.optString("endpoint").takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            } ?: "http://127.0.0.1:11434"
            val models = runCatching { MementoApp.summary.ollamaModels(endpoint) }.getOrElse { JSONArray() }
            ok(models)
        }
        "get_network_environment" -> ok(JSONObject().put("offline", false))
        "get_onboarding_status" -> ok(JSONObject().put("completed", MementoApp.prefs.get("onboarding") == "1"))
        "onboarding_should_run" -> ok(MementoApp.prefs.get("onboarding") != "1")
        "complete_onboarding" -> { MementoApp.prefs.set("onboarding", "1"); ok(JSONObject.NULL) }
        "save_onboarding_status_cmd" -> ok(JSONObject.NULL)
        "get_summarization_preference" -> ok(JSONObject()
            .put("enabled", MementoApp.prefs.get("summarization_enabled") != "0")
            .put("automaticAllowed", MementoApp.prefs.get("summarization_automatic") != "0"))
        "set_summarization_enabled" -> {
            val enabled = args.optBoolean("enabled", true)
            MementoApp.prefs.set("summarization_enabled", if (enabled) "1" else "0")
            fire("summarization-preference-changed", JSONObject().put("enabled", enabled))
            ok(JSONObject().put("enabled", enabled))
        }
        "watch_outlook_calendar" -> ok(JSONObject().put("watching", false))

        // ---------- persisted configs (stored in app prefs) ----------
        "api_get_transcript_config" -> ok(configJson("transcript", JSONObject()
            .put("provider", "gigaam")
            .put("model", "gigaam-v3-${MementoApp.models.selected()}")
            .put("apiKey", JSONObject.NULL)))
        "api_save_transcript_config" -> {
            saveConfig("transcript", args)
            val model = args.optString("model")
            val variant = model.removePrefix("gigaam-v3-")
            if (args.optString("provider") == "gigaam" && MementoApp.models.variant(variant) != null) {
                MementoApp.asr.release()
                MementoApp.models.select(variant)
            }
            ok(JSONObject.NULL)
        }
        "api_get_model_config" -> ok(configJson("model", JSONObject()
            .put("provider", "builtin-ai")
            .put("model", "memento-local")
            .put("whisperModel", JSONObject.NULL)
            .put("apiKey", JSONObject.NULL)
            .put("ollamaEndpoint", JSONObject.NULL)))
        "api_save_model_config" -> {
            saveConfig("model", args)
            val provider = args.optString("provider")
            val key = args.optString("apiKey")
            if (provider.isNotBlank() && key.isNotBlank()) MementoApp.prefs.set("api_key_$provider", key)
            ok(JSONObject.NULL)
        }
        "api_get_custom_openai_config" -> ok(configJson("openai", JSONObject()
            .put("endpoint", JSONObject.NULL)
            .put("apiKey", JSONObject.NULL)
            .put("model", JSONObject.NULL)
            .put("maxTokens", JSONObject.NULL)
            .put("temperature", JSONObject.NULL)
            .put("topP", JSONObject.NULL)))
        "api_save_custom_openai_config" -> { saveConfig("openai", args); ok(JSONObject.NULL) }
        "api_test_custom_openai_connection" -> ok(MementoApp.summary.testCustom(args))
        "api_get_api_key" -> ok(MementoApp.prefs.get("api_key_${args.optString("provider")}") ?: "")
        "api_save_meeting_title" -> {
            val id = args.optString("meetingId")
            val title = args.optString("title")
            if (id.isNotEmpty()) MementoApp.db.renameMeeting(id, title)
            ok(JSONObject.NULL)
        }

        // ---------- collections ----------
        "list_collections" -> ok(MementoApp.db.listCollections())
        "create_collection" -> ok(MementoApp.db.createCollection(args.optString("name"), args.optString("kind", "manual")))
        "rename_collection" -> {
            MementoApp.db.renameCollection(args.optLong("collectionId"), args.optString("name"))
            ok(JSONObject.NULL)
        }
        "delete_collection" -> {
            MementoApp.db.deleteCollection(args.optLong("collectionId"))
            ok(JSONObject.NULL)
        }
        "set_collection_meetings" -> {
            val id = args.optLong("collectionId")
            val src = args.optJSONArray("meetingIds") ?: args.optJSONArray("meeting_ids") ?: JSONArray()
            val ids = ArrayList<String>()
            for (i in 0 until src.length()) ids.add(src.optString(i))
            MementoApp.db.setCollectionMeetings(id, ids)
            ok(JSONObject.NULL)
        }
        "list_collection_candidates" -> ok(MementoApp.db.meetings())
        "suggest_meeting_series" -> ok(JSONArray())
        "accept_series_suggestion" -> {
            val id = MementoApp.db.createCollection(args.optString("suggestedName"), "series")
            val source = args.optJSONArray("meetingIds") ?: JSONArray()
            val ids = (0 until source.length()).map { source.optString(it) }
            MementoApp.db.setCollectionMeetings(id, ids)
            ok(id)
        }
        "set_series_auto_add" -> {
            MementoApp.db.setKV("series_auto_add:${args.optLong("collectionId")}", args.optBoolean("enabled").toString())
            ok(args.optBoolean("enabled"))
        }
        "convert_collection_to_series" -> ok(JSONObject.NULL)
        "get_standup_series_digest" -> err("Standup digest needs transcript analysis, which is not available in this build")

        // ---------- summary / templates ----------
        "api_get_summary" -> {
            val id = args.optString("meetingId")
            val saved = MementoApp.db.summary(id)
            val hasTranscript = MementoApp.db.transcriptCount(id) > 0
            val pendingJob = runCatching { JSONObject(MementoApp.prefs.get(summaryKey(id)).orEmpty()) }.getOrNull()
            val transcriptionJob = MementoApp.prefs.get(processingKey(id))?.takeIf { it.isNotBlank() }
            val active = activeProcessing.contains(id)
            val pending = pendingJob != null || transcriptionJob != null || active
            if (saved == null && hasTranscript && !active &&
                (pendingJob == null || pendingJob.optLong("next_retry_at", 0L) <= System.currentTimeMillis())) {
                scheduleAutomaticSummary(id, pendingJob?.optBoolean("replace_title", false) ?: false)
            }
            // The original summary component renders its Participants stack only when
            // summary data exists. Supply a non-persisted empty-state document so the
            // same original component (avatars, morphing stack, rename actions) is used
            // before a real summary has been generated as well.
            val processing = saved == null && (pending || hasTranscript)
            val data = saved ?: JSONObject()
                .put("markdown", if (processing) "## Саммари\n\nСаммари готовится…" else "## Саммари\n\nСаммари пока недоступно")
                .put("short_title", "")
                .put("short_description", "")
                .put("participants", MementoApp.db.participantNames(id))
                .put("provider", if (processing) "processing" else "empty-state")
                .put("model", "none")
            ok(JSONObject()
                .put("data", data)
                .put("status", if (processing) "processing" else "completed")
                .put("unavailable", saved == null && !processing))
        }
        "api_get_meeting_summary_language" -> meetingLanguage(args, "summary")
        "api_save_meeting_summary_language" -> saveMeetingLanguage(args, "summary", "summaryLanguage")
        "api_get_meeting_detected_summary_language" -> meetingLanguage(args, "detected_summary")
        "api_save_meeting_detected_summary_language" -> saveMeetingLanguage(args, "detected_summary", "detectedSummaryLanguage")
        "api_save_meeting_summary" -> {
            val id = args.optString("meetingId")
            val data = args.optJSONObject("summary") ?: args.optJSONObject("data")
            if (id.isNotEmpty() && data != null) MementoApp.db.saveSummary(id, data)
            ok(JSONObject.NULL)
        }
        "api_cancel_summary" -> ok(JSONObject.NULL)
        "api_list_templates" -> ok(MementoApp.db.listPromptTemplates())
        "api_get_template_details" -> ok(MementoApp.db.promptTemplate(args.optString("templateId")) ?: JSONObject.NULL)
        "api_create_prompt_template" -> ok(MementoApp.db.createPromptTemplate(args.optString("name"), args.optString("prompt")))
        "api_update_prompt_template" -> ok(MementoApp.db.updatePromptTemplate(
            args.optString("templateId"), args.optString("name"), args.optString("prompt")))
        "api_delete_prompt_template" -> { MementoApp.db.deletePromptTemplate(args.optString("templateId")); ok(JSONObject.NULL) }
        "suggest_summary_template" -> ok(JSONObject()
            .put("templateId", JSONObject.NULL)
            .put("confidence", "low")
            .put("reasons", JSONArray()))

        // ---------- embeddings / indexing ----------
        "embedder_status" -> ok(JSONObject()
            .put("available_models", JSONArray())
            .put("downloaded", false)
            .put("selected", JSONObject.NULL)
            .put("available", false))
        "embedder_select_model" -> ok(JSONObject.NULL)
        "embedder_download_model" -> err("RAG models are not bundled in this build")
        "indexing_status" -> ok(JSONObject().put("status", "idle").put("indexed", 0).put("total", 0))
        "get_core_loop_status" -> ok(JSONObject().put("running", false).put("stage", "idle"))

        // ---------- speakers / diarization reads ----------
        "get_meeting_speakers" -> ok(MementoApp.db.speakers(args.optString("meetingId")))
        "get_audio_devices" -> ok(MementoApp.recorder.audioDevices())
        "get_meeting_diarization_review" -> ok(JSONObject()
            .put("segments", JSONArray())
            .put("speakers", JSONArray())
            .put("mixed_voices", JSONArray())
            .put("slot_limit_reached", false)
            .put("cascade_rechecked", false))
        "get_meeting_channel_shares" -> ok(JSONObject())
        "get_meeting_transcription_provenance" -> ok(JSONObject())
        "get_meeting_content_window_suggestion" -> ok(JSONObject())
        "list_speaker_name_candidates" -> ok(JSONArray())
        "scan_speaker_name_candidates" -> ok(JSONArray())
        "assign_segment_speaker" -> {
            val raw = args.opt("speakerId")
            val speaker = if (raw == null || raw == JSONObject.NULL || raw.toString().isBlank()) null else raw.toString().toIntOrNull()
            MementoApp.db.assignSpeaker(args.optString("meetingId"), args.optLong("transcriptId"), speaker)
            ok(JSONObject.NULL)
        }
        "add_and_assign_segment_speaker" -> {
            val speaker = MementoApp.db.addSpeaker(args.optString("displayName"))
            MementoApp.db.assignSpeaker(args.optString("meetingId"), args.optLong("transcriptId"), speaker)
            ok(JSONObject().put("speaker_id", speaker))
        }
        "rename_speaker" -> { MementoApp.db.renameSpeaker(args.optInt("speakerId"), args.optString("displayName")); ok(JSONObject.NULL) }
        "set_self_speaker" -> { MementoApp.db.setSelfSpeaker(args.optInt("speakerId"), args.optBoolean("isSelf")); ok(JSONObject.NULL) }
        "add_summary_participant" -> ok(JSONObject.NULL)
        "review_speaker_name_candidate" -> ok(JSONObject.NULL)
        "infer_meeting_speaker_names" -> ok(JSONArray())
        "correct_transcript_segment" -> {
            val input = args.optJSONObject("input") ?: args
            MementoApp.db.correctTranscript(input.optLong("transcriptId"), input.optString("correctedText"))
            ok(JSONObject.NULL)
        }
        "classify_transcript_reactions" -> ok(JSONArray())
        "api_get_meeting_memory_config" -> ok(MementoApp.db.meetingMemoryConfig(args.optString("meetingId")))
        "api_set_meeting_memory_config" -> ok(MementoApp.db.setMeetingMemoryConfig(
            args.optString("meetingId"),
            args.optString("memoryType", "general"),
            args.optString("sensitivity", "standard"),
            args.optString("summaryTemplateId", "standard_meeting")
        ))
        "api_process_transcript" -> {
            val meetingId = args.optString("meetingId")
            val saved = if (MementoApp.db.isFixtureMeeting(meetingId)) MementoApp.db.summary(meetingId) else null
            // Demo summaries are authored fixtures. Changing only the meeting type
            // must not run them through the local summarizer and overwrite the copy.
            ok(if (MementoApp.db.isFixtureMeeting(meetingId)) JSONObject()
                .put("process_id", "demo-preserved")
                .put("summary", saved ?: JSONObject()
                    .put("markdown", "## Саммари\n\nСаммари пока недоступно")
                    .put("provider", "fixture"))
            else MementoApp.summary.process(
                args,
                configJson("model", JSONObject().put("provider", "builtin-ai").put("model", "memento-local")),
                configJson("openai", JSONObject())
            ))
        }

        // ---------- analytics / rag reads ----------
        "generate_analytics_report" -> generateAnalytics(args)
        "get_analytics_report" -> ok(analyticsState(args.optString("meetingId")))
        "open_analytics_report" -> openAnalytics(args.optString("meetingId"), true)
        "download_analytics_report" -> openAnalytics(args.optString("meetingId"), false)
        "reveal_report_in_folder" -> { act.openFileLocation(File(args.optString("path"))); ok(JSONObject.NULL) }
        "cancel_analytics_report" -> ok(JSONObject.NULL)
        "submit_analytics_answers" -> ok(JSONObject.NULL)
        "submit_analytics_questions" -> ok(JSONObject.NULL)
        "rag_get_latest_session" -> {
            val input = args.optJSONObject("input") ?: JSONObject()
            val scope = input.optString("scope", "archive")
            val meetingId = input.optString("meeting_id")
            val key = if (scope == "meeting" && meetingId.isNotEmpty()) "meeting:$meetingId" else "archive"
            if (key == "archive") MementoApp.db.ensureKnowledgeBaseSession()
            ok(MementoApp.db.latestChatSession(key) ?: JSONObject.NULL)
        }
        "rag_ask" -> {
            val input = args.optJSONObject("input") ?: JSONObject()
            val query = input.optString("query").trim()
            val meetingId = input.optString("meeting_id")
            val scope = input.optString("scope", "archive")
            val sessionId = if (scope == "meeting" && meetingId.isNotEmpty())
                MementoApp.db.ensureSession("meeting:$meetingId", "Чат встречи")
            else MementoApp.db.ensureKnowledgeBaseSession()
            if (query.isEmpty()) {
                ok(JSONObject()
                    .put("session_id", sessionId)
                    .put("answer", "Введите вопрос по своим встречам.")
                    .put("citations", JSONArray())
                    .put("found", false)
                    .put("warning", JSONObject.NULL))
            } else {
                MementoApp.db.appendChatMessage(sessionId, "user", query)
                val hits = MementoApp.db.searchTranscripts(query, meetingId, 8)
                val citations = JSONArray()
                val preview = StringBuilder()
                for (i in 0 until hits.length()) {
                    val h = hits.getJSONObject(i)
                    citations.put(JSONObject()
                        .put("meeting_id", h.optString("meeting_id"))
                        .put("meeting_title", h.optString("meeting_title"))
                        .put("text", h.optString("text"))
                        .put("start_seconds", h.optDouble("start_seconds")))
                    if (i < 3) preview.append("• ").append(h.optString("text")).append("\n")
                }
                val context = MementoApp.db.aiContext(if (scope == "meeting") meetingId else null)
                val found = context.isNotBlank()
                val fallback = if (hits.length() > 0) "Нашёл в расшифровках:\n$preview"
                    else "В расшифровках ничего не нашлось по этому запросу."
                val answer = if (found) runCatching {
                    CloudAi.ask(
                        if (scope == "meeting") "meeting_chat" else "knowledge_chat",
                        "Ты — ассистент Memento. Отвечай только по предоставленным стенограммам. Не выдумывай факты. Если данных недостаточно, прямо скажи об этом. Отвечай кратко, естественно и на языке вопроса.",
                        "ВОПРОС:\n$query\n\nСТЕНОГРАММЫ:\n$context"
                    ).takeIf { it.isNotBlank() } ?: fallback
                }.getOrElse { fallback } else fallback
                MementoApp.db.appendChatMessage(sessionId, "assistant", answer)
                ok(JSONObject()
                    .put("session_id", sessionId)
                    .put("answer", answer)
                    .put("citations", citations)
                    .put("found", found)
                    .put("warning", JSONObject.NULL)
                    .put("diagnostics", JSONObject()))
            }
        }

        // ---------- desktop-only window / misc ----------
        "animate_main_window" -> ok(JSONObject.NULL)
        "set_main_window_min_width" -> ok(JSONObject.NULL)
        "open_external_url" -> { act.openUrl(args.optString("url")); ok(JSONObject.NULL) }
        "open_system_settings" -> ok(JSONObject.NULL)
        "update_location" -> ok(JSONObject.NULL)
        "log_update_failure", "resolve_managed_defaults_migration", "report_auto_listening_start" -> ok(JSONObject.NULL)
        "plugin:app|version" -> ok(BuildConfig.VERSION_NAME)
        "plugin:process|restart" -> ok(JSONObject.NULL)

        else -> {
            // Honest passthrough for read-only state commands; mutating/ML commands -> clear error.
            if (cmd.startsWith("plugin:store|") || cmd.startsWith("plugin:updater|") ||
                cmd.startsWith("plugin:resources|") || cmd.startsWith("plugin:dialog|") ||
                cmd.startsWith("plugin:fs|") || cmd.startsWith("plugin:shell|") ||
                cmd.startsWith("plugin:window|") || cmd.startsWith("plugin:webview|")) {
                ok(storeDispatch(cmd, args))
            } else if (isMlOrDesktopOnly(cmd)) {
                err(cmd + " is not available in this build")
            } else {
                ok(defaultValue(cmd))
            }
        }
    }

    private fun storeDispatch(cmd: String, args: JSONObject): Any? {
        val p = MementoApp.prefs
        return when (cmd) {
            "plugin:store|load", "plugin:store|get_store" -> JSONObject().put("rid", 1)
            "plugin:store|get" -> {
                val key = args.optString("key")
                val raw = p.get(key)
                JSONArray().put(parseStoredValue(raw) ?: JSONObject.NULL).put(raw != null)
            }
            "plugin:store|set" -> {
                val key = args.optString("key")
                val value = args.opt("value")
                if (key.isNotEmpty() && value != null && value != JSONObject.NULL) p.set(key, value.toString())
                JSONObject.NULL
            }
            "plugin:store|has" -> p.keys().contains(args.optString("key"))
            "plugin:store|keys" -> JSONArray(p.keys())
            "plugin:store|values", "plugin:store|entries" -> JSONArray()
            "plugin:store|length" -> p.keys().size
            "plugin:store|clear", "plugin:store|delete", "plugin:store|reset", "plugin:store|save", "plugin:store|reload" -> JSONObject.NULL
            "plugin:app|version" -> BuildConfig.VERSION_NAME
            else -> JSONObject.NULL
        }
    }

    private fun submitFeedback(args: JSONObject, callbackId: String) {
        val submission = args.optJSONObject("submission") ?: JSONObject()
        Thread {
            val result = try {
                val payload = JSONObject()
                    .put("categories", submission.optJSONArray("categories") ?: JSONArray())
                    .put("message", submission.optString("message"))
                    .put("include_logs", submission.optBoolean("include_logs", false))
                    .put("submission_id", submission.optString("submission_id"))
                    .put("platform", "android")
                    .put("app_version", BuildConfig.VERSION_NAME)
                val logs = submission.optString("logs")
                if (logs.isNotBlank()) payload.put("logs", logs)
                postFormspree(payload)
                ok(JSONObject.NULL)
            } catch (e: Throwable) {
                err("Не удалось отправить отзыв: " + (e.message ?: "ошибка сети"))
            }
            if (callbackId.isNotBlank() && callbackId != "__none") act.reply(callbackId, result)
        }.apply { isDaemon = true }.start()
    }

    private fun postFormspree(payload: JSONObject) {
        val connection = URL("https://formspree.io/f/mvkkyval").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = (connection.errorStream ?: connection.inputStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $code ${body.take(200)}")
            }
            connection.inputStream?.close()
        } finally {
            connection.disconnect()
        }
    }

    private fun analyticsFile(meetingId: String) = File(app.filesDir, "reports/analytics-$meetingId.json")

    private fun analyticsState(meetingId: String): Any {
        val file = analyticsFile(meetingId)
        return if (file.isFile) JSONObject()
            .put("meeting_id", meetingId).put("report_id", "local-$meetingId")
            .put("status", "completed").put("stage_index", 11).put("total_stages", 11)
            .put("json_path", file.absolutePath).put("html_path", file.absolutePath).put("error", JSONObject.NULL)
        else JSONObject.NULL
    }

    private fun generateAnalytics(args: JSONObject): JSONObject {
        val meetingId = args.optString("meetingId")
        if (meetingId.isBlank()) return err("Не выбрана встреча")
        val reportId = "local-$meetingId"
        Thread {
            try {
                fire("analytics-report-progress", JSONObject().put("meeting_id", meetingId)
                    .put("report_id", reportId).put("label", "Анализ расшифровки")
                    .put("stage_index", 3).put("total_stages", 11))
                val segments = MementoApp.db.transcriptSegments(meetingId)
                if (segments.length() == 0) throw IllegalStateException("В стенограмме пока нет реплик")
                val speakerIds = LinkedHashMap<Int, String>()
                val transcript = JSONArray()
                for (i in 0 until segments.length()) {
                    val segment = segments.getJSONObject(i)
                    val text = segment.optString("text").trim()
                    if (text.isEmpty() || text.none { it.isLetterOrDigit() }) continue
                    val nativeId = segment.optInt("speaker_id", 101)
                    val stableId = speakerIds.getOrPut(nativeId) { "speaker_${speakerIds.size + 1}" }
                    val startMs = (segment.optDouble("audio_start_time", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
                    val endMs = (segment.optDouble("audio_end_time", startMs / 1000.0) * 1000.0).toLong().coerceAtLeast(startMs)
                    transcript.put(JSONObject()
                        .put("speaker_id", stableId)
                        .put("start_ms", startMs)
                        .put("end_ms", endMs)
                        .put("text", text))
                }
                if (transcript.length() == 0) throw IllegalStateException("В стенограмме пока нет реплик")
                val file = analyticsFile(meetingId)
                file.parentFile?.mkdirs()
                file.writeText(JSONObject().put("transcript", transcript).toString(2), Charsets.UTF_8)
                fire("analytics-report-complete", JSONObject().put("meeting_id", meetingId)
                    .put("report_id", reportId).put("json_path", file.absolutePath).put("html_path", file.absolutePath))
            } catch (e: Throwable) {
                fire("analytics-report-error", JSONObject().put("meeting_id", meetingId)
                    .put("report_id", reportId).put("error", e.message ?: "Ошибка отчёта"))
            }
        }.apply { isDaemon = true }.start()
        return ok(JSONObject().put("report_id", reportId))
    }

    private fun openAnalytics(meetingId: String, open: Boolean): JSONObject {
        val file = analyticsFile(meetingId)
        if (!file.isFile) return err("Сначала сформируйте отчёт")
        if (open) {
            // A stock Android phone usually has no activity registered for JSON.
            // Showing the exact generated document in-app makes "Открыть" reliable
            // without depending on a vendor file manager or browser.
            act.showAnalyticsReport(file)
            return ok(file.absolutePath)
        }
        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            saveToDownloads("Аналитика-$meetingId.json", file.readBytes(), "application/json")
        else saveToAppDownloads("Аналитика-$meetingId.json", file.readBytes())
        if (target == null) return err("Не удалось сохранить отчёт")
        act.showDownloadComplete()
        return ok(target)
    }

    private fun html(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun downloadTranscriptMarkdown(args: JSONObject): JSONObject {
        val id = args.optString("meetingId")
        if (id.isBlank()) return err("Не выбрана встреча")
        val segments = MementoApp.db.transcriptSegments(id)
        if (segments.length() == 0) return err("В стенограмме пока нет реплик")
        var title = args.optString("title").trim()
        if (title.isEmpty()) {
            val meetings = MementoApp.db.meetings()
            for (i in 0 until meetings.length()) {
                val meeting = meetings.optJSONObject(i) ?: continue
                if (meeting.optString("id") == id) {
                    title = meeting.optString("title")
                    break
                }
            }
        }
        if (title.isBlank()) title = "Стенограмма"
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().ifBlank { "Стенограмма" }
        val rows = StringBuilder()
        for (i in 0 until segments.length()) {
            val segment = segments.optJSONObject(i) ?: continue
            val text = segment.optString("transcript").trim()
            if (text.isEmpty()) continue
            val speaker = segment.optString("speaker").trim().ifBlank { "Спикер" }
            val start = segment.optDouble("audio_start_time", 0.0).coerceAtLeast(0.0)
            val end = segment.optDouble("audio_end_time", start).coerceAtLeast(start)
            rows.append("<article><header><strong>").append(html(speaker)).append("</strong><time>")
                .append(clock(start)).append("–").append(clock(end)).append("</time></header><p>")
                .append(html(text)).append("</p></article>\n")
        }
        val document = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${html(title)}</title><style>
body{max-width:840px;margin:0 auto;padding:32px 20px;background:#fafafa;color:#171717;font:16px/1.5 system-ui,-apple-system,sans-serif}
h1{font-size:28px;margin:0 0 28px}article{background:#fff;border:1px solid #e7e7e7;border-radius:14px;padding:16px 18px;margin:12px 0}
header{display:flex;justify-content:space-between;gap:16px}time{color:#777;font-size:14px}p{margin:10px 0 0;white-space:pre-wrap}
@media(prefers-color-scheme:dark){body{background:#171717;color:#f5f5f5}article{background:#222;border-color:#333}time{color:#aaa}}
</style></head><body><h1>${html(title)}</h1>$rows</body></html>"""
        val bytes = document.toByteArray(Charsets.UTF_8)
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloads(safeTitle + ".html", bytes, "text/html") ?: saveToAppDownloads(safeTitle + ".html", bytes)
        } else {
            saveToAppDownloads(safeTitle + ".html", bytes)
        }
        if (saved != null) act.showDownloadComplete()
        return if (saved != null) ok(saved) else err("Не удалось сохранить стенограмму")
    }

    private fun clock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, secs)
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun saveToDownloads(name: String, bytes: ByteArray, mime: String): String? {
        return try {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("download stream is not available")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                uri.toString()
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun saveToAppDownloads(name: String, bytes: ByteArray): String? {
        return try {
            val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Throwable) {
            null
        }
    }

    private fun startDiarization(args: JSONObject): JSONObject {
        val id = args.optString("meetingId")
        val folder = args.optString("meetingFolderPath")
        val candidates = ArrayList<File>()
        MementoApp.db.audioPath(id)?.takeIf { it.isNotBlank() }?.let { candidates.add(File(it)) }
        if (folder.isNotBlank()) {
            val f = File(folder)
            if (f.isDirectory) {
                f.listFiles { file -> file.isFile && file.name.endsWith(".wav", true) }
                    ?.sortedBy { it.name }?.forEach { candidates.add(it) }
            } else candidates.add(f)
        }
        val audio = candidates.firstOrNull { it.isFile }
            ?: return err("Аудиофайл встречи не найден")
        if (!MementoApp.diarization.ready()) return err("Модель диаризации не загружена")
        return try {
            MementoApp.diarization.cancelFlag.set(false)
            val turns = MementoApp.diarization.diarize(audio) { p ->
                fire("diarization-progress", JSONObject().put("meeting_id", id).put("meetingId", id).put("progress", p))
            }
            if (turns.isEmpty()) return err("В записи не найдено речи для разделения голосов")
            MementoApp.db.applyDiarization(id, turns.map { Triple(it.start, it.end, it.speaker) })
            val speakerCount = turns.map { it.speaker }.distinct().size
            val total = MementoApp.db.transcriptCount(id)
            val assigned = MementoApp.db.assignedCount(id)
            if (assigned == 0) return err("Голоса найдены, но не удалось привязать их к репликам")
            fire("diarization-complete", JSONObject()
                .put("meeting_id", id)
                .put("meetingId", id)
                .put("segments", assigned))
            ok(JSONObject()
                .put("speaker_count", speakerCount)
                .put("assigned_segments", assigned)
                .put("total_segments", total))
        } catch (e: Throwable) {
            fire("diarization-error", JSONObject()
                .put("meeting_id", id)
                .put("meetingId", id)
                .put("error", e.message ?: e.javaClass.simpleName))
            err(e.message ?: "Не удалось определить говорящих")
        }
    }

    private fun parseStoredValue(raw: String?): Any? {
        if (raw == null) return null
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            try {
                JSONArray(text)
            } catch (e2: Exception) {
                when {
                    text == "true" -> true
                    text == "false" -> false
                    text.toLongOrNull() != null -> text.toLong()
                    text.toDoubleOrNull() != null -> text.toDouble()
                    else -> raw
                }
            }
        }
    }

    private fun wavPeak(file: File): Int {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(44)
                if (input.read(header) < 44) return 0
                val buf = ByteArray(8192)
                var peak = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    var i = 0
                    while (i + 1 < read) {
                        val v = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                        val mag = if (v >= 0) v else -v
                        if (mag > peak) peak = mag
                        i += 2
                    }
                }
                peak
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun startRetranscription(args: JSONObject): JSONObject {
        val id = args.optString("meetingId")
        val folder = args.optString("meetingFolderPath")
        val candidates = ArrayList<File>()
        MementoApp.db.audioPath(id)?.takeIf { it.isNotBlank() }?.let { candidates.add(File(it)) }
        if (folder.isNotBlank()) {
            val f = File(folder)
            if (f.isDirectory) {
                f.listFiles { file -> file.isFile && file.name.endsWith(".wav", true) }
                    ?.sortedBy { it.name }?.forEach { candidates.add(it) }
            } else candidates.add(f)
        }
        val audio = candidates.firstOrNull { it.isFile }
        if (audio == null) {
            fire("retranscription-error", JSONObject().put("meeting_id", id).put("meetingId", id).put("error", "Аудиофайл встречи не найден"))
            return ok(JSONObject.NULL)
        }
        val deleteAfter = args.optBoolean("deleteAudioAfter", false)
        if (!MementoApp.asr.ready()) {
            // Keep the job instead of losing it. It is resumed on the next activity start or
            // immediately after the speech model finishes downloading.
            persistRetranscriptionJob(id, audio, deleteAfter)
            ContextCompat.startForegroundService(app, Intent(app, ProcessingService::class.java))
            fire("retranscription-progress", JSONObject().put("meeting_id", id).put("meetingId", id)
                .put("stage", "waiting_for_model").put("progress", 0.0))
            return ok(JSONObject.NULL)
        }
        val peak = wavPeak(audio)
        if (peak in 0..120) {
            val payload = JSONObject()
                .put("meeting_id", id)
                .put("meetingId", id)
                .put("error", "В записи почти нет звука (пик $peak из 32768): микрофон не захватил речь")
            fire("retranscription-error", payload)
            return ok(JSONObject.NULL)
        }
        launchRetranscription(id, audio, deleteAfter)
        return ok(JSONObject.NULL)
    }

    private fun configJson(key: String, defaults: JSONObject): JSONObject {
        val raw = MementoApp.prefs.get("cfg_$key")
        if (raw.isNullOrBlank()) return defaults
        return try {
            val value = JSONObject(raw)
            if (key == "model" && value.optString("provider") == "gigaam") defaults else value
        } catch (e: Exception) { defaults }
    }

    private fun meetingLanguage(args: JSONObject, kind: String): JSONObject {
        val id = args.optString("meetingId")
        val language = MementoApp.db.getKV("meeting_language:$kind:$id")
        return ok(JSONObject().put("language", language?.takeIf { it.isNotBlank() } ?: JSONObject.NULL).put("storage", "metadata"))
    }

    private fun saveMeetingLanguage(args: JSONObject, kind: String, field: String): JSONObject {
        val id = args.optString("meetingId")
        val raw = args.opt(field)
        val language = if (raw == null || raw == JSONObject.NULL) "" else raw.toString()
        MementoApp.db.setKV("meeting_language:$kind:$id", language)
        return ok(JSONObject().put("language", language.takeIf { it.isNotBlank() } ?: JSONObject.NULL).put("storage", "metadata"))
    }

    private fun saveConfig(key: String, args: JSONObject) {
        val copy = JSONObject(args.toString())
        copy.remove("callbackId")
        MementoApp.prefs.set("cfg_$key", copy.toString())
    }

    private fun isMlOrDesktopOnly(cmd: String) = setOf(
    "api_process_transcript","rerun_meeting_refinement",
    "rag_ask","generate_analytics_report","builtin_ai_list_models","builtin_ai_download_model"
    ).contains(cmd)

    companion object {
        private val activeProcessing = ConcurrentHashMap.newKeySet<String>()
    }

    private fun defaultValue(cmd: String): Any? {
        return when {
            cmd.startsWith("list_") || cmd.endsWith("_list") || cmd.contains("models") -> JSONArray()
            cmd.contains("status") || cmd.contains("info") || cmd.startsWith("get_") -> JSONObject()
            else -> JSONObject.NULL
        }
    }
}
