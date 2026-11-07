package app.memento.mobile

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.jtransforms.fft.DoubleFFT_1D
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Nemotron 3 Diarization inference against the exported ONNX graph.
 *
 * The graph scores one window at a time:
 *   input_features [1, mel_frames, 128], cached_embeds [1, 0, 512], attention_mask [1, encoder_frames]
 * and returns logits [1, encoder_frames * 8, 8] plus chunk_embeds.
 *
 * Windows are scored independently and speaker slots are stitched by the activity overlap between
 * neighbouring windows; this keeps the implementation small enough for a phone while preserving
 * who-spoke-when across the whole recording.
 */
class Diarization(private val models: Models) {

    data class Turn(val start: Double, val end: Double, val speaker: Int, val score: Float)

    private class WavInfo(val dataOffset: Long, val dataSize: Long, val rate: Int, val channels: Int, val bits: Int)
    private class WindowResult(
        val logits: FloatArray,
        val logitFrames: Int,
        val embeds: FloatArray,
        val embedFrames: Int
    )

    /** Host-side Arrival-Order Speaker Cache. The exported ONNX graph deliberately contains
     * only one stateless model step, so keeping just the last encoder frames resets speaker
     * identities every few windows. This mirrors the cache/FIFO policy used by Nemotron. */
    private inner class SpeakerCache {
        private val cacheCapacity = 264
        private val fifoCapacity = 40
        private val updatePeriod = 300
        private val silenceFramesPerSpeaker = 1
        private val budget = cacheCapacity / nSpk - silenceFramesPerSpeaker
        private val minPositive = floor(budget * 0.5).toInt()
        private val strongCount = floor(budget * 0.75).toInt()
        private val weakCount = floor(budget * 1.5).toInt()
        private var cacheEmbeds = FloatArray(0)
        private var cacheProbs = FloatArray(0)
        private var fifoEmbeds = FloatArray(0)
        private var compressed = false

        val frames: Int get() = cacheEmbeds.size / dModel + fifoEmbeds.size / dModel

        fun embeds(): FloatArray {
            val output = FloatArray(cacheEmbeds.size + fifoEmbeds.size)
            System.arraycopy(cacheEmbeds, 0, output, 0, cacheEmbeds.size)
            System.arraycopy(fifoEmbeds, 0, output, cacheEmbeds.size, fifoEmbeds.size)
            return output
        }

        private fun pooledProbabilities(logits: FloatArray, frames: Int): FloatArray {
            val encoderFrames = frames / 8
            val output = FloatArray(encoderFrames * nSpk)
            for (f in 0 until encoderFrames) for (s in 0 until nSpk) {
                var sum = 0f
                for (i in 0 until 8) sum += sigmoid(logits[(f * 8 + i) * nSpk + s])
                output[f * nSpk + s] = sum / 8f
            }
            return output
        }

        private fun frameScores(probs: FloatArray, frames: Int): Array<FloatArray> {
            val scores = Array(nSpk) { FloatArray(frames) { Float.NEGATIVE_INFINITY } }
            val positive = IntArray(nSpk)
            for (f in 0 until frames) {
                var complements = 0.0
                for (s in 0 until nSpk) complements += ln(maxOf(1.0 - probs[f * nSpk + s], 0.25))
                for (s in 0 until nSpk) {
                    val p = probs[f * nSpk + s].toDouble()
                    if (p <= 0.5) continue
                    val value = ln(maxOf(p, 0.25)) - ln(maxOf(1.0 - p, 0.25)) + complements - ln(0.5)
                    scores[s][f] = value.toFloat()
                    if (value > 0.0) positive[s]++
                }
            }
            for (s in 0 until nSpk) if (positive[s] >= minPositive) {
                for (f in 0 until frames) if (scores[s][f] <= 0f) scores[s][f] = Float.NEGATIVE_INFINITY
            }
            fun boost(count: Int, amount: Float) {
                for (s in 0 until nSpk) {
                    scores[s].indices.sortedByDescending { scores[s][it] }.take(minOf(count, frames)).forEach {
                        if (scores[s][it].isFinite()) scores[s][it] += amount
                    }
                }
            }
            boost(strongCount, (-2.0 * ln(0.5)).toFloat())
            boost(weakCount, (-ln(0.5)).toFloat())
            return scores
        }

        private fun compress(embeds: FloatArray, probs: FloatArray, frames: Int): Pair<FloatArray, FloatArray> {
            data class Candidate(val speaker: Int, val frame: Int, val score: Float)
            val scores = frameScores(probs, frames)
            val latestFrom = cacheCapacity.coerceAtMost(frames)
            for (s in 0 until nSpk) for (f in latestFrom until frames) {
                if (scores[s][f].isFinite()) scores[s][f] += 0.05f
            }
            val candidates = ArrayList<Candidate>(frames * nSpk + nSpk * silenceFramesPerSpeaker)
            for (s in 0 until nSpk) {
                for (f in 0 until frames) candidates.add(Candidate(s, f, scores[s][f]))
                repeat(silenceFramesPerSpeaker) { candidates.add(Candidate(s, frames, Float.POSITIVE_INFINITY)) }
            }
            val selected = candidates.sortedByDescending { it.score }.take(cacheCapacity)
                .sortedWith(compareBy<Candidate> { it.speaker }.thenBy { it.frame })
            val nextEmbeds = FloatArray(selected.size * dModel)
            val nextProbs = FloatArray(selected.size * nSpk)
            val silence = silenceEmbedding ?: FloatArray(dModel)
            selected.forEachIndexed { index, candidate ->
                if (candidate.frame == frames) {
                    System.arraycopy(silence, 0, nextEmbeds, index * dModel, dModel)
                } else {
                    System.arraycopy(embeds, candidate.frame * dModel, nextEmbeds, index * dModel, dModel)
                    System.arraycopy(probs, candidate.frame * nSpk, nextProbs, index * nSpk, nSpk)
                }
            }
            return nextEmbeds to nextProbs
        }

        fun update(result: WindowResult, chunkFrames: Int) {
            val oldCacheFrames = cacheEmbeds.size / dModel
            val oldFifoFrames = fifoEmbeds.size / dModel
            val pooled = pooledProbabilities(result.logits, result.logitFrames)
            val availableChunk = minOf(chunkFrames, result.embedFrames)
            val appended = FloatArray(fifoEmbeds.size + availableChunk * dModel)
            System.arraycopy(fifoEmbeds, 0, appended, 0, fifoEmbeds.size)
            System.arraycopy(result.embeds, 0, appended, fifoEmbeds.size, availableChunk * dModel)
            val totalFifoFrames = oldFifoFrames + availableChunk
            if (totalFifoFrames <= fifoCapacity) {
                fifoEmbeds = appended
                return
            }
            val popped = minOf(totalFifoFrames, maxOf(updatePeriod, totalFifoFrames - fifoCapacity))
            val inputFrames = result.logitFrames / 8
            fun probabilitySlice(from: Int, count: Int): FloatArray {
                if (count <= 0) return FloatArray(0)
                val safe = minOf(count, (inputFrames - from).coerceAtLeast(0))
                return pooled.copyOfRange(from * nSpk, (from + safe) * nSpk)
            }
            val fifoProbs = FloatArray(totalFifoFrames * nSpk)
            val oldProb = probabilitySlice(oldCacheFrames, oldFifoFrames)
            System.arraycopy(oldProb, 0, fifoProbs, 0, oldProb.size)
            val newProb = probabilitySlice(oldCacheFrames + oldFifoFrames, availableChunk)
            System.arraycopy(newProb, 0, fifoProbs, oldFifoFrames * nSpk, newProb.size)
            val stored = if (compressed) cacheProbs else probabilitySlice(0, oldCacheFrames)
            val candidateFrames = oldCacheFrames + popped
            val candidateEmbeds = FloatArray(candidateFrames * dModel)
            val candidateProbs = FloatArray(candidateFrames * nSpk)
            System.arraycopy(cacheEmbeds, 0, candidateEmbeds, 0, cacheEmbeds.size)
            System.arraycopy(appended, 0, candidateEmbeds, cacheEmbeds.size, popped * dModel)
            System.arraycopy(stored, 0, candidateProbs, 0, minOf(stored.size, oldCacheFrames * nSpk))
            System.arraycopy(fifoProbs, 0, candidateProbs, oldCacheFrames * nSpk, popped * nSpk)
            if (candidateFrames > cacheCapacity) {
                val next = compress(candidateEmbeds, candidateProbs, candidateFrames)
                cacheEmbeds = next.first
                cacheProbs = next.second
                compressed = true
            } else {
                cacheEmbeds = candidateEmbeds
                cacheProbs = candidateProbs
            }
            fifoEmbeds = appended.copyOfRange(popped * dModel, appended.size)
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = Any()
    private var session: OrtSession? = null

    private val sampleRate = 16000
    private val nFft = 512
    private val winLen = 400
    private val hop = 160
    private val nMels = 128
    private val nSpk = 8
    private val dModel = 512

    private val windowFrames = 3040      // 30.4 s: chunk_len(340) + right_context(40), 80 ms frames
    private val overlapFrames = 320      // 3.2 s shared with the next window
    private val rightContextEmbedFrames = 40
    private val minTurnFrames = 20       // 200 ms

    private var melIdx: Array<IntArray>? = null
    private var melWeight: Array<FloatArray>? = null
    private var window: FloatArray? = null
    private var silenceEmbedding: FloatArray? = null
    private var fft: DoubleFFT_1D? = null

    val cancelFlag = AtomicBoolean(false)

    private fun dir() = File(models.root, "nemotron")

    fun ready(): Boolean =
        File(dir(), "model.onnx").isFile &&
            File(dir(), "model.onnx_data").isFile &&
            File(dir(), "meta.json").isFile

    private fun readFloats(name: String): FloatArray {
        val bytes = File(dir(), name).readBytes()
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        bb.asFloatBuffer().get(out)
        return out
    }

    private fun ensureLoaded() {
        synchronized(lock) {
            if (session != null) return
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(File(dir(), "model.onnx").absolutePath, opts)
            window = readFloats("window.f32")
            silenceEmbedding = readFloats("sil_emb.f32").also {
                require(it.size == dModel) { "sil_emb.f32 size ${it.size} != $dModel" }
            }
            val bank = readFloats("mel_fbank.f32")
            buildSparseBank(bank)
            fft = DoubleFFT_1D(nFft.toLong())
        }
    }

    fun release() {
        synchronized(lock) {
            session?.close()
            session = null
        }
    }

    /** A dense 257x128 bank times 90 000 frames is too slow on a phone; keep only non-zero taps. */
    private fun buildSparseBank(bank: FloatArray) {
        val idx = ArrayList<IntArray>(nMels)
        val wgt = ArrayList<FloatArray>(nMels)
        for (m in 0 until nMels) {
            val ii = ArrayList<Int>()
            val ww = ArrayList<Float>()
            for (k in 0..nFft / 2) {
                // Nemotron's exported filterbank is stored mel-major [128, 257].
                val v = bank[m * (nFft / 2 + 1) + k]
                if (v > 1e-8f) {
                    ii.add(k)
                    ww.add(v)
                }
            }
            idx.add(ii.toIntArray())
            wgt.add(ww.toFloatArray())
        }
        melIdx = idx.toTypedArray()
        melWeight = wgt.toTypedArray()
    }

    private fun readIntLe(f: RandomAccessFile): Int {
        val b = ByteArray(4)
        f.readFully(b)
        return (b[0].toInt() and 0xff) or
            ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or
            ((b[3].toInt() and 0xff) shl 24)
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun i32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun wavInfo(file: File): WavInfo {
        RandomAccessFile(file, "r").use { f ->
            val len = f.length()
            require(len > 44) { "Аудиофайл слишком короткий" }
            val head = ByteArray(12)
            f.readFully(head)
            require(
                String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
            ) { "Файл не является WAV" }
            var pos = 12L
            var channels = 1
            var rate = 16000
            var bits = 16
            var dataOffset = -1L
            var dataSize = 0L
            while (pos + 8 <= len) {
                f.seek(pos)
                val idb = ByteArray(4)
                f.readFully(idb)
                val size = readIntLe(f).toLong() and 0xffffffffL
                val id = String(idb, Charsets.US_ASCII)
                if (id == "fmt ") {
                    val fmt = ByteArray(minOf(size, 64L).toInt())
                    f.readFully(fmt)
                    channels = u16(fmt, 2)
                    rate = i32(fmt, 4)
                    bits = u16(fmt, 14)
                } else if (id == "data") {
                    dataOffset = pos + 8
                    dataSize = minOf(size, len - dataOffset)
                    break
                }
                pos += 8 + size + (size and 1L)
            }
            require(dataOffset > 0 && dataSize > 0) { "В WAV не найден блок данных" }
            return WavInfo(dataOffset, dataSize, rate, channels, bits)
        }
    }

    private fun readPcm(file: File, info: WavInfo, startFrame: Int, frames: Int): FloatArray {
        require(info.bits == 16 && info.channels == 1) { "Нужен WAV 16 кГц, моно, 16 бит" }
        val startByte = info.dataOffset + startFrame.toLong() * hop * 2
        val want = frames.toLong() * hop * 2
        val available = (info.dataSize - (startByte - info.dataOffset)).coerceIn(0L, want).toInt()
        if (available < 4) return FloatArray(0)
        val buf = ByteArray(available)
        RandomAccessFile(file, "r").use { f ->
            f.seek(startByte)
            var read = 0
            while (read < available) {
                val n = f.read(buf, read, available - read)
                if (n <= 0) break
                read += n
            }
        }
        val n = available / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = buf[i * 2].toInt() and 0xff
            val hi = buf[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768.0f
        }
        return out
    }

    private fun logMel(samples: FloatArray): Array<FloatArray> {
        val w = window ?: return emptyArray()
        val idx = melIdx ?: return emptyArray()
        val weight = melWeight ?: return emptyArray()
        if (samples.size < winLen) return emptyArray()

        val pre = FloatArray(samples.size)
        var prev = 0f
        for (i in samples.indices) {
            pre[i] = samples[i] - 0.97f * prev
            prev = samples[i]
        }
        val pad = nFft / 2
        val padded = FloatArray(pre.size + 2 * pad)
        System.arraycopy(pre, 0, padded, pad, pre.size)
        val frames = (padded.size - winLen) / hop + 1
        if (frames <= 1) return emptyArray()

        val transformer = fft ?: DoubleFFT_1D(nFft.toLong()).also { fft = it }
        val buf = DoubleArray(nFft)
        val power = DoubleArray(nFft / 2 + 1)
        val out = Array(frames - 1) { FloatArray(nMels) }
        val offset = (nFft - winLen) / 2
        for (t in 0 until frames - 1) {
            if (t % 200 == 0 && cancelFlag.get()) throw InterruptedException("cancelled")
            java.util.Arrays.fill(buf, 0.0)
            val base = t * hop + offset
            for (i in 0 until winLen) buf[i] = padded[base + i].toDouble() * w[i]
            transformer.realForward(buf)
            power[0] = buf[0] * buf[0]
            power[nFft / 2] = buf[1] * buf[1]
            for (k in 1 until nFft / 2) {
                val re = buf[2 * k]
                val im = buf[2 * k + 1]
                power[k] = re * re + im * im
            }
            val row = out[t]
            for (m in 0 until nMels) {
                var acc = 0.0
                val ids = idx[m]
                val ws = weight[m]
                for (j in ids.indices) acc += power[ids[j]] * ws[j]
                row[m] = ln(acc + 5.960464477539063e-08).toFloat()
            }
        }
        return out
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

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

    private fun runWindow(feats: Array<FloatArray>, cachedEmbeds: FloatArray, cachedFrames: Int): WindowResult {
        val t = feats.size
        val flat = FloatArray(t * nMels)
        for (i in 0 until t) System.arraycopy(feats[i], 0, flat, i * nMels, nMels)

        val featureTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(flat), longArrayOf(1, t.toLong(), nMels.toLong())
        )
        val cacheFrames = cachedFrames.coerceAtLeast(0)
        val cacheData = if (cachedEmbeds.size >= cacheFrames * dModel) {
            cachedEmbeds
        } else {
            FloatArray(0)
        }
        val cachedTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(cacheData), longArrayOf(1, cacheFrames.toLong(), dModel.toLong())
        )
        val maskFrames = cacheFrames + (t + 7) / 8
        val maskTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(maskFrames) { 1L }), longArrayOf(1, maskFrames.toLong())
        )
        val feeds = HashMap<String, OnnxTensor>()
        feeds["input_features"] = featureTensor
        feeds["cached_embeds"] = cachedTensor
        feeds["attention_mask"] = maskTensor
        try {
            session!!.run(feeds).use { res ->
                val logits = res[0] as OnnxTensor
                val shape = logits.info.shape
                val frames = shape[1].toInt()
                val spk = shape[2].toInt()
                val raw = copyFloats(logits)
                val embTensor = res[1] as OnnxTensor
                val embShape = embTensor.info.shape
                val embedFrames = if (embShape.size > 1) embShape[1].toInt() else 0
                return WindowResult(raw, frames, copyFloats(embTensor), embedFrames)
            }
        } finally {
            feeds.values.forEach { it.close() }
        }
    }

    fun diarize(wav: File, onProgress: (Double) -> Unit): List<Turn> {
        require(ready()) { "Модель диаризации не загружена" }
        ensureLoaded()
        cancelFlag.set(false)
        val info = wavInfo(wav)
        require(info.rate == sampleRate) { "Нужен WAV 16 кГц" }
        val totalFrames = (info.dataSize / 2 / hop).toInt()
        require(totalFrames > minTurnFrames) { "Запись слишком короткая" }

        val labels = IntArray(totalFrames) { -1 }
        // chunk_embeds are the acoustic speaker representations used by Sortformer. Keep a
        // high-confidence centroid for every output slot so that a voice which is accidentally
        // reopened in another slot later in the meeting can be folded back to one stable ID.
        val speakerEmbeddingSums = Array(nSpk) { DoubleArray(dModel) }
        val speakerEmbeddingCounts = IntArray(nSpk)
        var start = 0
        val cache = SpeakerCache()
        val mainWindowFrames = windowFrames - overlapFrames

        while (start < totalFrames) {
            if (cancelFlag.get()) throw InterruptedException("cancelled")
            val end = minOf(totalFrames, start + windowFrames)
            val n = end - start
            if (n < minTurnFrames) break
            val feats = logMel(readPcm(wav, info, start, n))
            if (feats.size < minTurnFrames) {
                start += mainWindowFrames
                continue
            }
            val cachedFrames = cache.frames
            val window = runWindow(feats, cache.embeds(), cachedFrames)
            val hasLookAhead = end < totalFrames
            val chunkEncoderFrames = if (hasLookAhead) {
                minOf((mainWindowFrames + 7) / 8, window.embedFrames)
            } else window.embedFrames
            val logitOffset = cachedFrames * 8
            val availableLogits = (window.logitFrames - logitOffset).coerceAtLeast(0)
            val commitFrames = minOf(
                totalFrames - start,
                chunkEncoderFrames * 8,
                availableLogits
            )
            for (i in 0 until commitFrames) {
                var best = -1
                var bestScore = 0.25f
                val base = (logitOffset + i) * nSpk
                for (speaker in 0 until nSpk) {
                    val probability = sigmoid(window.logits[base + speaker])
                    if (probability > bestScore) {
                        bestScore = probability
                        best = speaker
                    }
                }
                labels[start + i] = best
            }
            val committedEmbedFrames = minOf(window.embedFrames, (commitFrames + 7) / 8)
            for (frame in 0 until committedEmbedFrames) {
                val probability = FloatArray(nSpk)
                val from = frame * 8
                val until = minOf(commitFrames, from + 8)
                if (until <= from) continue
                for (i in from until until) {
                    val base = (logitOffset + i) * nSpk
                    for (speaker in 0 until nSpk) probability[speaker] += sigmoid(window.logits[base + speaker])
                }
                for (speaker in 0 until nSpk) probability[speaker] /= (until - from).toFloat()
                val order = probability.indices.sortedByDescending { probability[it] }
                val winner = order.first()
                val runnerUp = order.getOrElse(1) { winner }
                // Ambiguous/overlapped frames are intentionally excluded: they pull two real
                // voices towards the same centroid and would make a false merge more likely.
                if (probability[winner] < 0.58f || probability[runnerUp] > 0.34f) continue
                val embedOffset = frame * dModel
                if (embedOffset + dModel > window.embeds.size) continue
                for (d in 0 until dModel) speakerEmbeddingSums[winner][d] += window.embeds[embedOffset + d]
                speakerEmbeddingCounts[winner]++
            }
            cache.update(window, chunkEncoderFrames)
            if (commitFrames <= 0) break
            start += commitFrames
            runCatching { onProgress(minOf(1.0, start.toDouble() / totalFrames)) }
        }
        val stableLabels = mergeDuplicateSpeakerSlots(labels, speakerEmbeddingSums, speakerEmbeddingCounts)
        return toTurns(smoothSpeakerChanges(consolidateSpeakerFragments(stableLabels)))
    }

    private fun mergeDuplicateSpeakerSlots(
        source: IntArray,
        sums: Array<DoubleArray>,
        embeddingCounts: IntArray
    ): IntArray {
        val activity = IntArray(nSpk)
        source.forEach { if (it in 0 until nSpk) activity[it]++ }
        val parent = IntArray(nSpk) { it }
        fun root(value: Int): Int {
            var current = value
            while (parent[current] != current) current = parent[current]
            return current
        }
        fun cosine(a: Int, b: Int): Double {
            if (embeddingCounts[a] < 4 || embeddingCounts[b] < 4) return -1.0
            var dot = 0.0
            var aa = 0.0
            var bb = 0.0
            for (d in 0 until dModel) {
                val av = sums[a][d] / embeddingCounts[a]
                val bv = sums[b][d] / embeddingCounts[b]
                dot += av * bv
                aa += av * av
                bb += bv * bv
            }
            return if (aa <= 1e-12 || bb <= 1e-12) -1.0 else dot / sqrt(aa * bb)
        }
        val active = (0 until nSpk).filter { activity[it] > 0 }
        for (i in active.indices) for (j in i + 1 until active.size) {
            val a = active[i]
            val b = active[j]
            val similarity = cosine(a, b)
            val large = maxOf(activity[a], activity[b]).coerceAtLeast(1)
            val fragmentRatio = minOf(activity[a], activity[b]).toDouble() / large
            AppLog.info(
                "diarization::identity",
                "slots $a/$b similarity=${"%.3f".format(java.util.Locale.US, similarity)} activity=${activity[a]}/${activity[b]}"
            )
            // Require near-identical embeddings for two sizeable speakers. For a short slot,
            // a slightly lower threshold is enough because slot reopening is the common case.
            if (similarity >= 0.975 || (similarity >= 0.92 && fragmentRatio <= 0.40)) {
                val keep = if (activity[a] >= activity[b]) root(a) else root(b)
                val remove = if (keep == root(a)) root(b) else root(a)
                if (keep != remove) parent[remove] = keep
            }
        }
        val output = source.copyOf()
        for (i in output.indices) if (output[i] in 0 until nSpk) output[i] = root(output[i])
        return output
    }

    /**
     * Remove only short A-B-A islands and tiny silent holes. This is deliberately more
     * conservative than a general majority filter: genuine short answers from another person
     * remain intact, while the common one-speaker false flip no longer splits a sentence.
     */
    private fun smoothSpeakerChanges(source: IntArray): IntArray {
        val output = source.copyOf()
        data class Run(val from: Int, val to: Int, val speaker: Int)
        fun runs(): List<Run> {
            val result = ArrayList<Run>()
            var from = 0
            while (from < output.size) {
                val speaker = output[from]
                var to = from + 1
                while (to < output.size && output[to] == speaker) to++
                result.add(Run(from, to, speaker))
                from = to
            }
            return result
        }

        // A short drop in model activity inside continuous speech should not create two turns.
        for (run in runs()) {
            if (run.speaker >= 0 || run.to - run.from > 45 || run.from == 0 || run.to == output.size) continue
            val left = output[run.from - 1]
            val right = output[run.to]
            if (left >= 0 && left == right) java.util.Arrays.fill(output, run.from, run.to, left)
        }
        // Nemotron can briefly light up a second slot for the same voice. Only collapse it when
        // the surrounding identity is identical, which avoids swallowing real speaker hand-offs.
        for (run in runs()) {
            if (run.speaker < 0 || run.to - run.from > 120 || run.from == 0 || run.to == output.size) continue
            val left = output[run.from - 1]
            val right = output[run.to]
            if (left >= 0 && left == right && left != run.speaker) {
                java.util.Arrays.fill(output, run.from, run.to, left)
            }
        }
        return output
    }

    private fun consolidateSpeakerFragments(source: IntArray): IntArray {
        val counts = HashMap<Int, Int>()
        var speechFrames = 0
        for (speaker in source) if (speaker >= 0) {
            counts[speaker] = (counts[speaker] ?: 0) + 1
            speechFrames++
        }
        if (counts.size <= 1) return source
        // Slots that amount only to a few seconds or a tiny fraction of the meeting
        // are normally stitching artefacts, not additional attendees.
        // Keep genuine short answers ("да", "понял", etc.). The old three-second /
        // 3.5% threshold silently folded a real second participant into speaker 1.
        val minimum = maxOf(120, (speechFrames * 0.02).toInt())
        val keep = counts.entries.sortedByDescending { it.value }
            .filter { it.value >= minimum }
            .take(4)
            .map { it.key }
            .toMutableSet()
        if (keep.isEmpty()) keep.add(counts.maxBy { it.value }.key)
        if (keep.size == counts.size) return source

        val output = source.copyOf()
        for (i in output.indices) {
            if (output[i] < 0 || output[i] in keep) continue
            var replacement = -1
            var distance = 1
            while (replacement < 0 && (i - distance >= 0 || i + distance < output.size)) {
                if (i - distance >= 0 && output[i - distance] in keep) replacement = output[i - distance]
                if (replacement < 0 && i + distance < output.size && output[i + distance] in keep) replacement = output[i + distance]
                distance++
            }
            output[i] = if (replacement >= 0) replacement else keep.first()
        }
        val ordered = LinkedHashMap<Int, Int>()
        for (i in output.indices) if (output[i] >= 0) {
            val next = ordered.size
            output[i] = ordered.getOrPut(output[i]) { next }
        }
        return output
    }

    private fun toTurns(labels: IntArray): List<Turn> {
        val turns = ArrayList<Turn>()
        var cur = -1
        var start = 0
        for (i in 0..labels.size) {
            val v = if (i < labels.size) labels[i] else -2
            if (v != cur) {
                if (cur >= 0 && i - start >= minTurnFrames) {
                    turns.add(Turn(start * 0.01, i * 0.01, cur, 1f))
                }
                cur = v
                start = i
            }
        }
        return turns
    }
}
