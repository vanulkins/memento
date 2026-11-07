package app.memento.mobile

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes Android-supported audio containers (including MP3/M4A/OGG/FLAC/WAV) to 16 kHz mono WAV. */
object AudioDecoder {
    data class Info(val durationSeconds: Double, val mime: String, val sampleRate: Int, val channels: Int)

    fun inspect(source: File): Info {
        require(source.isFile && source.length() > 0) { "Файл не найден или пуст" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track = audioTrack(extractor)
            require(track >= 0) { "В файле нет поддерживаемой аудиодорожки" }
            val format = extractor.getTrackFormat(track)
            return Info(
                durationSeconds = format.longOr(MediaFormat.KEY_DURATION, 0L) / 1_000_000.0,
                mime = format.stringOr(MediaFormat.KEY_MIME, "audio/unknown"),
                sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0),
                channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0)
            )
        } finally {
            extractor.release()
        }
    }

    fun normalize(source: File, target: File, onProgress: (Double) -> Unit = {}): Info {
        val info = inspect(source)
        target.parentFile?.mkdirs()
        if (isPcm16kMonoWav(source)) {
            source.copyTo(target, overwrite = true)
            onProgress(1.0)
            return info.copy(sampleRate = 16000, channels = 1)
        }

        val raw = File(target.parentFile, target.name + ".pcm.part")
        if (raw.exists()) raw.delete()
        if (target.exists()) target.delete()
        try {
            val decoded = decodePcm(source, raw, info.durationSeconds, onProgress)
            resampleToWav(raw, target, decoded.first, decoded.second)
            onProgress(1.0)
            return Info(target.length().coerceAtLeast(44L).minus(44L) / 32000.0, "audio/wav", 16000, 1)
        } catch (e: Throwable) {
            target.delete()
            throw e
        } finally {
            raw.delete()
        }
    }

    private fun decodePcm(source: File, raw: File, duration: Double, onProgress: (Double) -> Unit): Pair<Int, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = audioTrack(extractor)
            require(track >= 0) { "В файле нет поддерживаемой аудиодорожки" }
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.stringOr(MediaFormat.KEY_MIME, "")
            require(mime.startsWith("audio/")) { "Неподдерживаемый формат: $mime" }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 16000)
            var channels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            BufferedOutputStream(FileOutputStream(raw), 256 * 1024).use { out ->
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            sampleRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                            val encoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, 2)
                            require(encoding == 2) { "Аудиодекодер вернул неподдерживаемый PCM encoding $encoding" }
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (index >= 0) {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(index)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                out.write(bytes)
                                if (duration > 0 && info.presentationTimeUs >= 0) {
                                    onProgress((info.presentationTimeUs / 1_000_000.0 / duration).coerceIn(0.0, 0.92))
                                }
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            require(raw.length() > 0) { "Не удалось декодировать аудио" }
            return sampleRate to channels
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            codec?.release()
            extractor.release()
        }
    }

    private fun resampleToWav(raw: File, target: File, sourceRate: Int, channels: Int) {
        require(sourceRate > 0 && channels > 0) { "Некорректные параметры аудио" }
        RandomAccessFile(target, "rw").use { wav ->
            wav.setLength(0)
            wav.write(wavHeader(0))
            var written = 0L
            val frameBytes = channels * 2
            val buffer = ByteArray(frameBytes * 4096)
            var sourceFrame = 0L
            var nextOutputAt = 0.0
            BufferedInputStream(FileInputStream(raw), 256 * 1024).use { input ->
                var carry = ByteArray(0)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0 && carry.isEmpty()) break
                    val data = if (carry.isEmpty()) buffer.copyOf(count.coerceAtLeast(0)) else carry + buffer.copyOf(count.coerceAtLeast(0))
                    val complete = data.size / frameBytes * frameBytes
                    var offset = 0
                    while (offset < complete) {
                        var mixed = 0
                        for (channel in 0 until channels) {
                            val p = offset + channel * 2
                            mixed += ((data[p + 1].toInt() shl 8) or (data[p].toInt() and 0xff)).toShort().toInt()
                        }
                        val sample = (mixed / channels).coerceIn(-32768, 32767)
                        while (sourceFrame.toDouble() + 1e-9 >= nextOutputAt) {
                            wav.write(sample and 0xff)
                            wav.write((sample shr 8) and 0xff)
                            written += 2
                            nextOutputAt += sourceRate / 16000.0
                        }
                        sourceFrame++
                        offset += frameBytes
                    }
                    carry = if (complete < data.size) data.copyOfRange(complete, data.size) else ByteArray(0)
                    if (count <= 0) break
                }
            }
            wav.seek(0)
            wav.write(wavHeader(written))
        }
    }

    private fun isPcm16kMonoWav(file: File): Boolean {
        if (file.length() < 44) return false
        val header = ByteArray(44)
        file.inputStream().use { if (it.read(header) != header.size) return false }
        fun text(offset: Int, length: Int) = String(header, offset, length, Charsets.US_ASCII)
        fun le16(offset: Int) = (header[offset].toInt() and 0xff) or ((header[offset + 1].toInt() and 0xff) shl 8)
        fun le32(offset: Int) = le16(offset) or (le16(offset + 2) shl 16)
        return text(0, 4) == "RIFF" && text(8, 4) == "WAVE" && le16(20) == 1 &&
            le16(22) == 1 && le32(24) == 16000 && le16(34) == 16
    }

    private fun audioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).stringOr(MediaFormat.KEY_MIME, "").startsWith("audio/")) return i
        }
        return -1
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private fun MediaFormat.longOr(key: String, fallback: Long): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(fallback) else fallback

    private fun MediaFormat.stringOr(key: String, fallback: String): String =
        if (containsKey(key)) runCatching { getString(key) ?: fallback }.getOrDefault(fallback) else fallback

    private fun wavHeader(dataLength: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt((36L + dataLength).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1.toShort())
        putShort(1.toShort())
        putInt(16000)
        putInt(32000)
        putShort(2.toShort())
        putShort(16.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }.array()
}
