package app.memento.mobile

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MementoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        AppLog.init(this)
        AppLog.info("mobile", "Application setup complete")
        AppLog.info("database::manager", "Opening application database")
        installCrashLogger()
        try {
            db.seedDemoIfNeeded(this)
            AppLog.info("database::setup", "Database initialized successfully")
        } catch (e: Throwable) {
            AppLog.error("database::setup", "Demo seed failed: ${e.message}")
            android.util.Log.e("MEMENTO", "demo seed", e)
        }
        try { db.ensureKnowledgeBaseSession() } catch (e: Throwable) {
            AppLog.error("database::setup", "Knowledge base initialization failed: ${e.message}")
            android.util.Log.e("MEMENTO", "knowledge base seed", e)
        }
    }

    private fun installCrashLogger() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val text = dump(e)
            AppLog.error("mobile::crash", text)
            try {
                getSharedPreferences("memento_crash", Context.MODE_PRIVATE).edit().putString("last", text).apply()
            } catch (_: Throwable) {}
            try { File(filesDir, "crash.txt").writeText(text) } catch (_: Throwable) {}
            android.util.Log.e("MEMENTO", text)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    private fun dump(e: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 6) {
            cur.printStackTrace(pw)
            cur = cur.cause
            depth++
        }
        pw.flush()
        return sw.toString()
    }

    companion object {
        lateinit var app: MementoApp
        val db: Db by lazy { Db(app) }
        val models: Models by lazy { Models(app) }
        val asr: Asr by lazy { Asr(models) }
        val diarization: Diarization by lazy { Diarization(models) }
        val recorder: Recorder by lazy { Recorder(app) }
        val summary: SummaryEngine by lazy { SummaryEngine() }
        val prefs: Prefs by lazy { Prefs(app) }
        @Volatile var projection: android.media.projection.MediaProjection? = null
        fun takeLastCrash(): String? {
            val sp = app.getSharedPreferences("memento_crash", Context.MODE_PRIVATE)
            val v = sp.getString("last", null)
            if (v != null) sp.edit().remove("last").apply()
            return v
        }
    }
}
