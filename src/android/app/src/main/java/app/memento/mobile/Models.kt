package app.memento.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Local models for on-device speech recognition (GigaAM v3) and diarization (Nemotron 3).
 * Small constant tensors (mel filterbanks, window, silence embedding, meta) ship inside the APK
 * and are copied from assets; only the large ONNX weights are downloaded.
 */
class Models(private val ctx: Context) {
    private val exec = Executors.newFixedThreadPool(2)
    val root: File get() = File(ctx.filesDir, "models").apply { mkdirs() }

    data class Variant(
        val id: String,
        val label: String,
        val dir: String,
        val files: List<Spec>,
        val sizeMb: Long,
        val totalHintMb: Long
    )

    data class Spec(
        val name: String,
        val size: Long,
        val sha256: String?,
        val urls: List<String>,
        val asset: String? = null
    )

    private val gigaRev = "322c3b29492673eb7d0b434bfa9dfb8653e34d02"
    private val hfGiga = "https://huggingface.co/istupakov/gigaam-v3-onnx/resolve/$gigaRev"
    private val obsGiga = "https://obs.ru-moscow-1.hc.sbercloud.ru/d-ssdev-crowd/function_descriptions/memento/models/gigaam-v3/$gigaRev"
    private val hfNemo = "https://huggingface.co/onnx-community/Nemotron-3-Diarization-ONNX/resolve/main"

    // Hugging Face is considerably faster from mobile networks; the OBS copy is a fallback.
    private fun giga(n: String) = listOf("$hfGiga/$n", "$obsGiga/$n")

    private val GIGA_RNNT_FP32 = Variant(
        "e2e-rnnt-fp32", "GigaAM Duo · Русский + английский · CPU", "gigaam-v3/e2e-rnnt-fp32",
        listOf(
            Spec("v3_e2e_rnnt_vocab.txt", 13354L, "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7", giga("v3_e2e_rnnt_vocab.txt")),
            Spec("v3_e2e_rnnt_encoder.onnx", 885084534L, "cd60b3764a832e8560ae6d3ad0b10adc1a42ffae412b9476f25620aae4f4a508", giga("v3_e2e_rnnt_encoder.onnx")),
            Spec("v3_e2e_rnnt_decoder.onnx", 4599910L, "7b0a16d67fd2cb37061decc93c69e364a9ab27afee3c57495d55b1c974cf7231", giga("v3_e2e_rnnt_decoder.onnx")),
            Spec("v3_e2e_rnnt_joint.onnx", 2712896L, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c", giga("v3_e2e_rnnt_joint.onnx")),
            Spec("mel_fbank_v3.f32", 41216L, null, emptyList(), "models/mel_fbank_v3.f32"),
            Spec("window_v3.f32", 1280L, null, emptyList(), "models/window_v3.f32")
        ),
        891L, 891L
    )

    private val GIGA_RNNT_INT8 = Variant(
        "e2e-rnnt-int8-cpu", "GigaAM Duo · Русский + английский · CPU", "gigaam-v3/e2e-rnnt-int8",
        listOf(
            Spec("v3_e2e_rnnt_vocab.txt", 13354L, "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7", giga("v3_e2e_rnnt_vocab.txt")),
            Spec("v3_e2e_rnnt_encoder.int8.onnx", 224570477L, null, giga("v3_e2e_rnnt_encoder.int8.onnx")),
            Spec("v3_e2e_rnnt_decoder.int8.onnx", 1159170L, null, giga("v3_e2e_rnnt_decoder.int8.onnx")),
            Spec("v3_e2e_rnnt_joint.int8.onnx", 687791L, null, giga("v3_e2e_rnnt_joint.int8.onnx")),
            Spec("mel_fbank_v3.f32", 41216L, null, emptyList(), "models/mel_fbank_v3.f32"),
            Spec("window_v3.f32", 1280L, null, emptyList(), "models/window_v3.f32")
        ),
        218L, 218L
    )

    // Both choices use the same quantized weights. The difference is the ONNX Runtime
    // execution provider, so switching does not download a second 218 MB copy.
    private val GIGA_RNNT_NNAPI = GIGA_RNNT_INT8.copy(
        id = "e2e-rnnt-int8-nnapi",
        label = "GigaAM Duo · Русский + английский · Neural Engine"
    )

    private val GIGA_CTC_FP32 = Variant(
        "e2e-ctc-fp32", "CTC fp32", "gigaam-v3/e2e-ctc-fp32",
        listOf(
            Spec("v3_e2e_ctc_vocab.txt", 2007L, null, giga("v3_e2e_ctc_vocab.txt")),
            Spec("v3_e2e_ctc.onnx", 885950079L, null, giga("v3_e2e_ctc.onnx")),
            Spec("mel_fbank_v3.f32", 41216L, null, emptyList(), "models/mel_fbank_v3.f32"),
            Spec("window_v3.f32", 1280L, null, emptyList(), "models/window_v3.f32")
        ),
        845L, 845L
    )

    private val GIGA_CTC_INT8 = Variant(
        "e2e-ctc-int8", "CTC int8", "gigaam-v3/e2e-ctc-int8",
        listOf(
            Spec("v3_e2e_ctc_vocab.txt", 2007L, null, giga("v3_e2e_ctc_vocab.txt")),
            Spec("v3_e2e_ctc.int8.onnx", 224893347L, null, giga("v3_e2e_ctc.int8.onnx")),
            Spec("mel_fbank_v3.f32", 41216L, null, emptyList(), "models/mel_fbank_v3.f32"),
            Spec("window_v3.f32", 1280L, null, emptyList(), "models/window_v3.f32")
        ),
        215L, 215L
    )

    private val NEMO = Variant(
        "nemotron", "Nemotron 3 diarization", "nemotron",
        listOf(
            Spec("model.onnx", 303466L, null, listOf("$hfNemo/onnx/model.onnx")),
            Spec("model.onnx_data", 398184448L, null, listOf("$hfNemo/onnx/model.onnx_data")),
            Spec("mel_fbank.f32", 131584L, null, emptyList(), "models/mel_fbank.f32"),
            Spec("window.f32", 1600L, null, emptyList(), "models/window.f32"),
            Spec("sil_emb.f32", 2048L, null, emptyList(), "models/sil_emb.f32"),
            Spec("meta.json", 868L, null, emptyList(), "models/meta.json")
        ),
        380L, 380L
    )

    @Volatile private var downloadingId: String? = null
    @Volatile private var cancelRequested = false
    @Volatile var progress: Double = 0.0
    @Volatile var downloadedMb: Double = 0.0
    val events = ArrayList<(String, JSONObject) -> Unit>()

    private fun fire(e: String, o: JSONObject) {
        try {
            events.forEach { it(e, o) }
        } catch (ignored: Throwable) {
        }
    }

    // The desktop selector exposes exactly two GigaAM Duo choices. Android's NNAPI is
    // the equivalent hardware-accelerated execution path for the second choice.
    private val transcriptionVariants = listOf(GIGA_RNNT_INT8, GIGA_RNNT_NNAPI)

    fun variants(): List<Variant> = transcriptionVariants

    fun diarizationVariant(): Variant = NEMO

    fun selectedVariant(): Variant =
        transcriptionVariants.firstOrNull { it.id == selected() } ?: GIGA_RNNT_INT8

    fun variant(id: String): Variant? = transcriptionVariants.firstOrNull { it.id == id }

    fun variantDir(v: Variant): File = dir(v)

    private fun dir(v: Variant) = File(root, v.dir).apply { mkdirs() }

    private fun ensureBundled(v: Variant) {
        val d = dir(v)
        for (s in v.files) {
            val asset = s.asset ?: continue
            val out = File(d, s.name)
            if (out.isFile && (s.size <= 0 || out.length() == s.size)) continue
            try {
                ctx.assets.open(asset).use { input -> out.outputStream().use { input.copyTo(it) } }
            } catch (e: Throwable) {
                android.util.Log.e("MEMENTO", "bundled asset failed: " + asset, e)
            }
        }
    }

    fun present(v: Variant): Boolean {
        ensureBundled(v)
        return v.files.all { File(dir(v), it.name).isFile }
    }

    fun selected(): String {
        val stored = ctx.getSharedPreferences("memento_models", Context.MODE_PRIVATE)
            .getString("gigaam.selected", GIGA_RNNT_INT8.id)
            ?.let {
                when (it) {
                    "e2e-rnnt-int8-nnapi" -> GIGA_RNNT_NNAPI.id
                    GIGA_RNNT_INT8.id -> GIGA_RNNT_INT8.id
                    else -> GIGA_RNNT_INT8.id // migrate v3/fp32/legacy variants
                }
            }
        return if (stored != null && variant(stored) != null) stored else GIGA_RNNT_INT8.id
    }

    fun statusJson(): JSONObject {
        val selectedVariant = selectedVariant()
        val arr = JSONArray()
        for (v in transcriptionVariants) {
            ensureBundled(v)
            arr.put(
                JSONObject()
                    .put("id", v.id)
                    .put("label", v.label)
                    .put("size_mb", v.sizeMb)
                    .put("present", present(v))
                    .put("downloadable", true)
                    .put("bilingual", true)
                    .put("neural_engine", v.id == GIGA_RNNT_NNAPI.id)
            )
        }
        return JSONObject()
            .put("variants", arr)
            .put("selected", selected())
            .put("model_present", present(selectedVariant))
            .put("loaded", present(selectedVariant))
            .put("loaded_variant", if (present(selectedVariant)) selectedVariant.id else JSONObject.NULL)
            .put("neural_engine_supported", true)
            .put("downloading", downloadingId == selectedVariant.id)
            .put("download_progress", if (downloadingId == selectedVariant.id) progress else 0.0)
            .put("downloaded_mb", downloadedMb)
    }

    fun diarStatusJson(): JSONObject = JSONObject()
        .put("available", present(NEMO))
        .put("download_mb", NEMO.sizeMb)
        .put("downloading", downloadingId == NEMO.id)

    fun select(id: String) {
        require(variant(id) != null) { "Неизвестный вариант GigaAM: $id" }
        ctx.getSharedPreferences("memento_models", Context.MODE_PRIVATE)
            .edit().putString("gigaam.selected", id).apply()
    }

    fun download(v: Variant) {
        if (downloadingId == v.id) return
        AppLog.info("models::download", "Starting ${if (v === NEMO) "speaker recognition" else "transcription"} model download")
        downloadingId = v.id
        cancelRequested = false
        progress = 0.0
        downloadedMb = 0.0
        exec.submit {
            var total = 0L
            for (s in v.files) if (s.asset == null) total += s.size
            var done = v.files.filter { it.asset == null }.sumOf { s ->
                val out = File(dir(v), s.name)
                val part = File(dir(v), s.name + ".part")
                when {
                    out.isFile && (s.size <= 0 || out.length() == s.size) -> out.length()
                    part.isFile -> part.length().coerceAtMost(s.size)
                    else -> 0L
                }
            }
            var lastEmit = 0L
            val isTranscription = v !== NEMO
            val event = if (isTranscription) "gigaam-download-progress" else "diarization-download-progress"

            fun emit(force: Boolean) {
                val now = System.currentTimeMillis()
                if (!force && now - lastEmit < 250) return
                lastEmit = now
                val fraction = if (total > 0) done.toDouble() / total else 0.0
                progress = fraction
                downloadedMb = done / 1048576.0
                fire(
                    event,
                    JSONObject()
                        .put("id", v.id)
                        .put("downloaded", done)
                        .put("total", total)
                        .put("stage", "downloading")
                        .put("percent", fraction * 100.0)
                )
            }

            var failure: String? = null
            try {
                ensureBundled(v)
                emit(true)
                for (s in v.files) {
                    if (cancelRequested) throw IllegalStateException("cancelled")
                    if (s.asset != null) continue
                    val out = File(dir(v), s.name)
                    fetch(s, out, total) { delta ->
                        done += delta
                        emit(false)
                    }
                }
            } catch (e: Throwable) {
                failure = e.message ?: e.javaClass.simpleName
            }
            val cancelled = cancelRequested
            downloadingId = null
            cancelRequested = false
            val readyEvent = if (isTranscription) "gigaam-ready" else "diarization-ready"
            val errorEvent = if (isTranscription) "gigaam-download-error" else "diarization-download-error"
            if (failure == null) {
                progress = 1.0
                emit(true)
                AppLog.info("models::download", "Model download completed successfully")
                fire(readyEvent, JSONObject().put("id", v.id))
            } else if (cancelled || failure.contains("cancelled", ignoreCase = true)) {
                AppLog.warn("models::download", "Model download cancelled")
                fire(errorEvent, JSONObject().put("error", "CANCELLED:" + v.id))
            } else {
                AppLog.error("models::download", "Model download failed: $failure")
                fire(errorEvent, JSONObject().put("error", failure))
            }
        }
    }

    fun cancel() {
        if (downloadingId == null) return
        cancelRequested = true
        fire("gigaam-download-error", JSONObject().put("error", "CANCELLED:" + downloadingId))
    }

    fun delete(v: Variant) {
        val d = File(root, v.dir)
        d.listFiles()?.forEach { if (it.name !in setOf("mel_fbank_v3.f32", "window_v3.f32", "mel_fbank.f32", "window.f32", "sil_emb.f32", "meta.json")) it.delete() }
    }

    private fun fetch(s: Spec, out: File, total: Long, onBytes: (Long) -> Unit) {
        if (out.isFile && (s.size <= 0 || out.length() == s.size) &&
            (s.sha256 == null || sha(out) == s.sha256)
        ) {
            return
        }
        var last: Throwable? = null
        for (u in s.urls) {
            if (cancelRequested) throw IllegalStateException("cancelled")
            try {
                transfer(u, s, out, onBytes)
                return
            } catch (e: Throwable) {
                last = e
                android.util.Log.w("MEMENTO", "download failed: $u -> ${e.message}")
            }
        }
        throw last ?: IllegalStateException("no mirror for " + s.name)
    }

    private fun transfer(url: String, s: Spec, out: File, onBytes: (Long) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000
        c.readTimeout = 45000
        c.setRequestProperty("User-Agent", "MementoMobile")
        val part = File(out.parentFile, out.name + ".part")
        try {
            var from = if (part.isFile) part.length() else 0L
            if (from > 0 && s.size > 0 && from >= s.size) {
                from = 0
                part.delete()
            }
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code != 200 && code != 206) throw IllegalStateException("http $code for $url")
            val stream = c.inputStream
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(if (code == 206) from else 0L)
                if (code != 206) {
                    if (from > 0) onBytes(-from)
                    raf.setLength(0)
                }
                stream.use { inp ->
                    val buf = ByteArray(256 * 1024)
                    var n: Int
                    while (inp.read(buf).also { n = it } > 0) {
                        if (cancelRequested) throw IllegalStateException("cancelled")
                        raf.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
            if (s.size > 0 && part.length() != s.size) throw IllegalStateException("size mismatch for " + s.name)
            if (s.sha256 != null && sha(part) != s.sha256) {
                part.delete()
                throw IllegalStateException("sha256 mismatch for " + s.name)
            }
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) throw IllegalStateException("rename failed for " + s.name)
        } finally {
            c.disconnect()
        }
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val b = ByteArray(1 shl 20)
            var n = ins.read(b)
            while (n > 0) {
                md.update(b, 0, n)
                n = ins.read(b)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
