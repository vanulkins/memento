package app.memento.mobile

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.jtransforms.fft.DoubleFFT_1D
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

class Asr(private val models: Models) {

    data class Segment(val start: Double, val end: Double, val text: String)

    class Result(val text: String, val segments: List<Segment>)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = Any()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var joiner: OrtSession? = null
    private var vocab: Map<Int, String>? = null
    private var melBank: FloatArray? = null
    private var window: FloatArray? = null
    private var loadedVariant: String? = null

    private val blank = 1024
    private val predHidden = 320
    private val maxTokensPerStep = 3
    private val winLen = 320
    private val hop = 160
    private val nBins = winLen / 2 + 1
    private val nMels = 64
    private val windowSec = 20.0
    private val windowHopSec = 19.0

    @Volatile var running = false
        private set

    @Volatile private var layoutFlip = false

    val cancelFlag = AtomicBoolean(false)

    private fun variant() = models.selectedVariant()

    private fun dir() = models.variantDir(variant())

    private fun isCtc(): Boolean = variant().id.contains("ctc")

    private fun isInt8(): Boolean = variant().id.contains("int8")

    private fun useNnapi(): Boolean = variant().id.contains("nnapi")

    private fun createSession(file: File): OrtSession {
        if (useNnapi()) {
            val accelerated = runCatching {
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    addNnapi()
                }
                env.createSession(file.absolutePath, opts)
            }
            if (accelerated.isSuccess) return accelerated.getOrThrow()
            android.util.Log.w("MEMENTO", "NNAPI could not load ${file.name}; using CPU", accelerated.exceptionOrNull())
        }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(file.absolutePath, opts)
    }

    private fun encoderName(): String = when {
        isCtc() && isInt8() -> "v3_e2e_ctc.int8.onnx"
        isCtc() -> "v3_e2e_ctc.onnx"
        isInt8() -> "v3_e2e_rnnt_encoder.int8.onnx"
        else -> "v3_e2e_rnnt_encoder.onnx"
    }

    private fun decoderName(): String =
        if (isInt8()) "v3_e2e_rnnt_decoder.int8.onnx" else "v3_e2e_rnnt_decoder.onnx"

    private fun joinerName(): String =
        if (isInt8()) "v3_e2e_rnnt_joint.int8.onnx" else "v3_e2e_rnnt_joint.onnx"

    private fun vocabName(): String = if (isCtc()) "v3_e2e_ctc_vocab.txt" else "v3_e2e_rnnt_vocab.txt"

    fun ready(): Boolean {
        val d = dir()
        if (!File(d, encoderName()).isFile || !File(d, vocabName()).isFile) return false
        return isCtc() || (File(d, decoderName()).isFile && File(d, joinerName()).isFile)
    }

    private fun loadVocab(): Map<Int, String> = vocab ?: run {
        val m = LinkedHashMap<Int, String>()
        File(dir(), vocabName()).forEachLine { line ->
            if (line.isNotBlank()) {
                val cut = line.lastIndexOf(' ')
                if (cut > 0) {
                    val id = line.substring(cut + 1).trim().toIntOrNull()
                    if (id != null) m[id] = line.substring(0, cut).replace('\u2581', ' ')
                }
            }
        }
        vocab = m
        m
    }

    private fun readFloats(name: String, expected: Int): FloatArray {
        val f = File(dir(), name)
        val bytes = f.readBytes()
        val fb = FloatBuffer.wrap(java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let {
            val arr = FloatArray(it.remaining()); it.get(arr); arr
        })
        require(fb.remaining() == expected) { "$name size ${fb.remaining()} != $expected" }
        return fb.array()
    }

    private fun ensureLoaded() {
        synchronized(lock) {
            val selected = variant().id
            if (encoder != null && loadedVariant == selected) return
            releaseLocked()
            encoder = createSession(File(dir(), encoderName()))
            if (!isCtc()) {
                decoder = createSession(File(dir(), decoderName()))
                joiner = createSession(File(dir(), joinerName()))
            }
            loadVocab()
            melBank = readFloats("mel_fbank_v3.f32", nBins * nMels)
            window = readFloats("window_v3.f32", winLen)
            loadedVariant = selected
        }
    }

    fun release() {
        synchronized(lock) {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        encoder?.close(); decoder?.close(); joiner?.close()
        encoder = null; decoder = null; joiner = null
        vocab = null; melBank = null; window = null; loadedVariant = null
    }

    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "audio file is too small" }
        var pos = 12
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            if (id == "data") { dataOffset = pos + 8; dataLen = minOf(size, bytes.size - dataOffset); break }
            pos += 8 + size + (size and 1)
        }
        require(dataOffset > 0 && dataLen > 1) { "wav data chunk not found" }
        val n = dataLen / 2
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = le16(bytes, dataOffset + i * 2) / 32768.0f
        return out
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun le16(b: ByteArray, o: Int): Int {
        val lo = b[o].toInt() and 0xff
        val hi = b[o + 1].toInt()
        return ((hi shl 8) or lo).toShort().toInt()
    }

    private fun features(samples: FloatArray, offset: Int, count: Int): Pair<FloatArray, Int> {
        val w = window!!
        val bank = melBank!!
        val fft = DoubleFFT_1D(winLen.toLong())
        val frames = if (count < winLen) 0 else (count - winLen) / hop + 1
        require(frames > 0) { "audio chunk is too short" }
        val out = FloatArray(nMels * frames)
        val buf = DoubleArray(winLen)
        val power = DoubleArray(nBins)
        for (t in 0 until frames) {
            val base = offset + t * hop
            for (i in 0 until winLen) buf[i] = samples[base + i].toDouble() * w[i]
            fft.realForward(buf)
            power[0] = buf[0] * buf[0]
            power[nBins - 1] = buf[1] * buf[1]
            for (k in 1 until nBins - 1) {
                val re = buf[2 * k]
                val im = buf[2 * k + 1]
                power[k] = re * re + im * im
            }
            for (m in 0 until nMels) {
                var acc = 0.0
                for (k in 0 until nBins) acc += power[k] * bank[k * nMels + m]
                val clipped = acc.coerceIn(1e-9, 1e9)
                out[m * frames + t] = Math.log(clipped).toFloat()
            }
            if (cancelFlag.get()) throw InterruptedException("cancelled")
        }
        return out to frames
    }

    private class Encoded(val frames: Int, val dim: Int, val data: FloatArray)

    private fun encode(samples: FloatArray, offset: Int, count: Int): Encoded {
        val (feat, frames) = features(samples, offset, count)
        val session = encoder!!
        val inputs = session.inputInfo
        val audioName = if (inputs.containsKey("audio_signal")) "audio_signal" else inputs.keys.first()
        val lenName = if (inputs.containsKey("length")) "length" else inputs.keys.firstOrNull { it != audioName }
        val audioInfo = inputs[audioName]?.info as? ai.onnxruntime.TensorInfo
        val dims = audioInfo?.shape ?: longArrayOf(-1, -1, -1)
        val timeMajor = (dims.size == 3 && dims[2] == nMels.toLong() && dims[1] != nMels.toLong()) != layoutFlip
        val data = if (timeMajor) {
            val arr = FloatArray(frames * nMels)
            for (m in 0 until nMels) for (t in 0 until frames) arr[t * nMels + m] = feat[m * frames + t]
            arr
        } else feat
        val shape = if (timeMajor) longArrayOf(1, frames.toLong(), nMels.toLong())
        else longArrayOf(1, nMels.toLong(), frames.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)).use { len ->
                val feed = HashMap<String, OnnxTensor>()
                feed[audioName] = input
                if (lenName != null) feed[lenName] = len
                val res = session.run(feed)
                try {
                    val enc = res[0] as OnnxTensor
                    val encShape = enc.info.shape
                    val channels = encShape[1].toInt()
                    val steps = encShape[2].toInt()
                    val encLen = if (res.size() > 1) {
                        val lens = res[1] as OnnxTensor
                        try { copyLongs(lens).firstOrNull()?.toInt() ?: steps } catch (e: Exception) { steps }
                    } else steps
                    val raw = copyFloats(enc)
                    val outLen = minOf(encLen, steps)
                    val t = FloatArray(outLen * channels)
                    for (c in 0 until channels) for (s in 0 until outLen) t[s * channels + c] = raw[c * steps + s]
                    return Encoded(outLen, channels, t)
                } finally {
                    res.close()
                }
            }
        }
    }

    private fun decodeFrames(enc: Encoded, emit: (Int, FloatArray, Int) -> Boolean) {
        var h = FloatArray(predHidden)
        var c = FloatArray(predHidden)
        var dec = FloatArray(predHidden)
        var prevToken = blank
        val encF = FloatArray(enc.dim)
        for (t in 0 until enc.frames) {
            System.arraycopy(enc.data, t * enc.dim, encF, 0, enc.dim)
            var emitted = 0
            while (true) {
                val jr = join(prevToken, h, c, encF)
                val logits = jr.logits
                var best = 0
                var bestVal = logits[0]
                for (i in 1 until logits.size) if (logits[i] > bestVal) { bestVal = logits[i]; best = i }
                if (best == blank) break
                prevToken = best
                h = jr.h; c = jr.c; dec = jr.dec
                emitted++
                if (!emit(t, dec, best)) return
                if (emitted >= maxTokensPerStep) break
            }
            if (cancelFlag.get()) throw InterruptedException("cancelled")
        }
    }

    private fun decodeCtc(samples: FloatArray, offset: Int, count: Int): Pair<List<String>, List<Double>> {
        val (feat, frames) = features(samples, offset, count)
        val session = encoder!!
        val inputs = session.inputInfo
        val audioName = if (inputs.containsKey("audio_signal")) "audio_signal" else inputs.keys.first()
        val lenName = if (inputs.containsKey("length")) "length" else inputs.keys.firstOrNull { it != audioName }
        val audioInfo = inputs[audioName]?.info as? ai.onnxruntime.TensorInfo
        val dims = audioInfo?.shape ?: longArrayOf(-1, -1, -1)
        val timeMajor = dims.size == 3 && dims[2] == nMels.toLong() && dims[1] != nMels.toLong()
        val data = if (timeMajor) {
            FloatArray(frames * nMels).also { arr ->
                for (m in 0 until nMels) for (t in 0 until frames) arr[t * nMels + m] = feat[m * frames + t]
            }
        } else feat
        val shape = if (timeMajor) longArrayOf(1, frames.toLong(), nMels.toLong())
        else longArrayOf(1, nMels.toLong(), frames.toLong())
        val pieces = ArrayList<String>()
        val times = ArrayList<Double>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)).use { len ->
                val feed = HashMap<String, OnnxTensor>()
                feed[audioName] = input
                if (lenName != null) feed[lenName] = len
                session.run(feed).use { result ->
                    val logits = result[0] as OnnxTensor
                    val outShape = logits.info.shape
                    require(outShape.size >= 3) { "unexpected CTC output shape: ${outShape.contentToString()}" }
                    val raw = copyFloats(logits)
                    val vocabSize = (loadVocab().keys.maxOrNull() ?: 255) + 1
                    val dim1 = outShape[outShape.size - 2].toInt()
                    val dim2 = outShape[outShape.size - 1].toInt()
                    val timeFirst = kotlin.math.abs(dim2 - vocabSize) <= kotlin.math.abs(dim1 - vocabSize)
                    val steps = if (timeFirst) dim1 else dim2
                    val classes = if (timeFirst) dim2 else dim1
                    var previous = -1
                    for (t in 0 until steps) {
                        var best = 0
                        var bestValue = Float.NEGATIVE_INFINITY
                        for (c in 0 until classes) {
                            val index = if (timeFirst) t * classes + c else c * steps + t
                            val value = raw.getOrElse(index) { Float.NEGATIVE_INFINITY }
                            if (value > bestValue) { bestValue = value; best = c }
                        }
                        if (best != 256 && best != previous) {
                            loadVocab()[best]?.takeUnless { it == "<unk>" || it == "<blk>" }?.let {
                                pieces.add(it)
                                times.add(offset / 16000.0 + t * 0.04)
                            }
                        }
                        previous = best
                    }
                }
            }
        }
        return pieces to times
    }

    private class JointResult(val logits: FloatArray, val h: FloatArray, val c: FloatArray, val dec: FloatArray)

    private fun join(token: Int, h: FloatArray, c: FloatArray, encFrame: FloatArray): JointResult {
        var hh = h
        var cc = c
        var dec = FloatArray(predHidden)
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(token.toLong())), longArrayOf(1, 1)).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(hh), longArrayOf(1, 1, predHidden.toLong())).use { ht ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(cc), longArrayOf(1, 1, predHidden.toLong())).use { ct ->
                    decoder!!.run(mapOf("x" to x, "h.1" to ht, "c.1" to ct)).use { res ->
                        dec = floats(res[0] as OnnxTensor, predHidden)
                        hh = floats(res[1] as OnnxTensor, predHidden)
                        cc = floats(res[2] as OnnxTensor, predHidden)
                    }
                }
            }
        }
        val encShape = longArrayOf(1, encFrame.size.toLong(), 1)
        val decShape = longArrayOf(1, predHidden.toLong(), 1)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(encFrame), encShape).use { e ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(dec), decShape).use { d ->
                joiner!!.run(mapOf("enc" to e, "dec" to d)).use { res ->
                    val t = res[0] as OnnxTensor
                    val size = t.info.shape.last().toInt()
                    return JointResult(floats(t, size), hh, cc, dec)
                }
            }
        }
    }

    private fun copyFloats(t: OnnxTensor): FloatArray {
        val out = ArrayList<Float>()
        fun walk(value: Any?) {
            when (value) {
                null -> return
                is Float -> out.add(value)
                is FloatArray -> for (v in value) out.add(v)
                is java.nio.FloatBuffer -> while (value.hasRemaining()) out.add(value.get())
                is Array<*> -> for (item in value) walk(item)
                else -> throw IllegalStateException("unexpected float tensor: " + value.javaClass.name)
            }
        }
        walk(t.value)
        return out.toFloatArray()
    }

    private fun copyLongs(t: OnnxTensor): LongArray {
        val out = ArrayList<Long>()
        fun walk(value: Any?) {
            when (value) {
                null -> return
                is Long -> out.add(value)
                is LongArray -> for (v in value) out.add(v)
                is Int -> out.add(value.toLong())
                is IntArray -> for (v in value) out.add(v.toLong())
                is java.nio.LongBuffer -> while (value.hasRemaining()) out.add(value.get())
                is Array<*> -> for (item in value) walk(item)
                else -> throw IllegalStateException("unexpected long tensor: " + value.javaClass.name)
            }
        }
        walk(t.value)
        return out.toLongArray()
    }

    private fun floats(t: OnnxTensor, n: Int): FloatArray {
        val a = copyFloats(t)
        return if (a.size == n) a else a.copyOf(n)
    }

    fun transcribe(wav: File, onProgress: (Double) -> Unit): Result {
        require(ready()) { "Выбранная модель GigaAM v3 не загружена" }
        ensureLoaded()
        running = true
        cancelFlag.set(false)
        try {
            val samples = readWav(wav)
            val total = samples.size.toDouble() / 16000.0
            val tokensText = ArrayList<String>()
            val tokensTime = ArrayList<Double>()
            var offset = 0
            layoutFlip = false
            val win = (windowSec * 16000).toInt()
            val step = (windowHopSec * 16000).toInt()
            while (offset < samples.size) {
                val count = minOf(win, samples.size - offset)
                if (count < winLen) break
                val chunkStart = offset / 16000.0
                var emitted = 0
                val parts = ArrayList<String>()
                val times = ArrayList<Double>()
                if (isCtc()) {
                    val decoded = decodeCtc(samples, offset, count)
                    parts.addAll(decoded.first)
                    times.addAll(decoded.second)
                    emitted = decoded.first.size
                } else {
                    val enc = encode(samples, offset, count)
                    decodeFrames(enc) { frame, _, token ->
                        emitted++
                        val piece = loadVocab()[token]
                        if (piece != null) {
                            parts.add(piece)
                            times.add(chunkStart + frame * 0.04)
                        }
                        true
                    }
                }
                tokensText.addAll(parts)
                tokensTime.addAll(times)
                runCatching { onProgress(minOf(1.0, (offset + count).toDouble() / samples.size)) }
                offset += step
            }
            val segments = buildSegments(tokensText, tokensTime, total)
            val text = segments.joinToString("\n") { it.text }.trim()
            return Result(text, segments)
        } finally {
            running = false
        }
    }

    private fun buildSegments(tokens: List<String>, times: List<Double>, total: Double): List<Segment> {
        if (tokens.isEmpty()) return emptyList()
        val out = ArrayList<Segment>()
        val sb = StringBuilder()
        var start = times[0]
        var prev = times[0]
        for (i in tokens.indices) {
            val t = times[i]
            if (sb.isNotEmpty() && (t - prev > 0.7 || sb.length > 320)) {
                out.add(Segment(start, prev, cleanup(sb.toString())))
                sb.setLength(0)
                start = t
            }
            sb.append(tokens[i])
            prev = t
        }
        if (sb.isNotEmpty()) out.add(Segment(start, minOf(total, prev + 0.2), cleanup(sb.toString())))
        return out.filter { it.text.isNotEmpty() }
    }

    private fun cleanup(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()
}
