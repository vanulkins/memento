package app.memento.mobile

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent application journal with the same text contract as desktop Memento. */
object AppLog {
    private const val MAX_BYTES = 1024 * 1024L
    private val lock = Any()
    private val clock = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd][HH:mm:ss", Locale.US) }
    private lateinit var context: Context
    private lateinit var file: File

    fun init(ctx: Context) {
        synchronized(lock) {
            context = ctx.applicationContext
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            file = File(dir, "memento.log")
            rotateIfNeeded()
        }
    }

    fun info(module: String, message: String) = write("INFO", module, message)
    fun warn(module: String, message: String) = write("WARN", module, message)
    fun error(module: String, message: String) = write("ERROR", module, message)

    private fun write(level: String, module: String, message: String) {
        if (!::file.isInitialized) return
        val clean = message.replace('\r', ' ').replace('\n', ' ')
        val line = "[${clock.get()!!.format(Date())}][$level][app_lib::$module] $clean\n"
        synchronized(lock) {
            runCatching {
                rotateIfNeeded()
                file.appendText(line)
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() <= MAX_BYTES) return
        val old = File(file.parentFile, "memento.log.1")
        if (old.exists()) old.delete()
        file.renameTo(old)
    }

    fun feedbackBundle(): String = synchronized(lock) {
        val sources = listOf(File(file.parentFile, "memento.log.1"), file).filter { it.isFile }
        if (sources.isEmpty()) return@synchronized ""
        sources.joinToString("\n\n") { source ->
            val sanitized = sanitize(source.readText())
            buildString {
                append("===== ")
                append(source.name)
                append(" =====\n")
                append(sanitized.text.trimEnd())
                append('\n')
                if (sanitized.removed > 0) {
                    append("\n[")
                    append(sanitized.removed)
                    append(" line(s) naming meetings, folders or devices were removed before sending]\n")
                }
            }
        }
    }

    private data class Sanitized(val text: String, val removed: Int)

    private fun sanitize(raw: String): Sanitized {
        val privateMarkers = listOf(
            "meeting_name", "meeting title", "meeting set", "meeting folder",
            "recording saved", "saving transcript", "source_path", "source path",
            "microphone:", "system audio:", "preferred devices:", "file://"
        )
        var removed = 0
        val kept = raw.lineSequence().mapNotNull { line ->
            val lower = line.lowercase(Locale.ROOT)
            if (privateMarkers.any(lower::contains)) {
                removed++
                null
            } else line
        }.joinToString("\n")

        var text = kept
        val internal = context.filesDir.parentFile?.absolutePath
        if (!internal.isNullOrBlank()) text = text.replace(internal, "/data/user/0/<user>")
        context.getExternalFilesDir(null)?.absolutePath?.substringBefore("/files")?.let {
            text = text.replace(it, "/storage/emulated/0/Android/data/<user>")
        }
        text = text
            .replace(Regex("(?i)(authorization|api[_-]?key|token|password|cookie|secret)(\\s*[=:]\\s*)[^\\s,;]+"), "\$1\$2<redacted>")
            .replace(Regex("(?i)https?://[^\\s/@]+:[^\\s/@]+@"), "https://<redacted>@")
        return Sanitized(text, removed)
    }
}
