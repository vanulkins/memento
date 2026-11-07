package app.memento.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioCapture(private val ctx: Context) {

    private val sampleRate = 16000
    private val channels = 1
    private val bitsPerSample = 16

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var level = 0.0
    @Volatile private var systemActive = false
    @Volatile private var dataBytes = 0L

    private var mic: AudioRecord? = null
    private var system: AudioRecord? = null
    private var thread: Thread? = null
    private var writer: RandomAccessFile? = null

    fun hasSystem(): Boolean = systemActive

    fun isPaused(): Boolean = paused

    fun seconds(): Double = dataBytes / (sampleRate * 2.0)

    fun currentLevel(): Double {
        if (paused) return 0.0
        return level
    }

    @SuppressLint("MissingPermission")
    fun start(file: File, projection: MediaProjection?): Boolean {
        if (running) return false
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val micRecord = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 4)
                .build()
        } catch (e: Exception) {
            android.util.Log.e("MEMENTO", "mic record failed", e)
            null
        }
        if (micRecord == null || micRecord.state != AudioRecord.STATE_INITIALIZED) {
            micRecord?.release()
            return false
        }
        preferredInput()?.let { device ->
            runCatching { micRecord.setPreferredDevice(device) }
                .onFailure { android.util.Log.w("MEMENTO", "preferred microphone unavailable", it) }
        }

        var systemRecord: AudioRecord? = null
        if (projection != null && Build.VERSION.SDK_INT >= 29) {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val record = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuffer * 4)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    systemRecord = record
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                android.util.Log.w("MEMENTO", "playback capture unavailable", e)
            }
        }

        val raf = try {
            RandomAccessFile(file, "rw")
        } catch (e: Exception) {
            micRecord.release()
            systemRecord?.release()
            return false
        }
        raf.setLength(0)
        raf.write(wavHeader(0))
        writer = raf
        mic = micRecord
        system = systemRecord
        dataBytes = 0
        level = 0.0
        systemActive = systemRecord != null
        running = true
        paused = false
        micRecord.startRecording()
        try { systemRecord?.startRecording() } catch (e: Exception) {
            android.util.Log.w("MEMENTO", "system start failed", e)
            systemRecord?.release()
            system = null
            systemActive = false
        }
        thread = Thread { loop() }.apply { start() }
        return true
    }

    private fun loop() {
        val chunk = 640
        val micBuffer = ShortArray(chunk)
        val systemBuffer = ShortArray(chunk)
        val mixed = ByteArray(chunk * 2)
        while (running) {
            if (paused) {
                Thread.sleep(40)
                continue
            }
            val micRead = try { mic?.read(micBuffer, 0, chunk) ?: 0 } catch (e: Exception) { -1 }
            val systemRead = if (system != null) {
                try { system!!.read(systemBuffer, 0, chunk, AudioRecord.READ_NON_BLOCKING) } catch (e: Exception) { 0 }
            } else 0
            if (micRead <= 0 && systemRead <= 0) {
                Thread.sleep(10)
                continue
            }
            var peak = 0
            for (i in 0 until chunk) {
                val m = if (i < micRead) micBuffer[i].toInt() else 0
                val s = if (i < systemRead) systemBuffer[i].toInt() else 0
                val sensitivity = if (MementoApp.prefs.get("vad.high_sensitivity") == "true") 1.75 else 1.0
                val value = ((m * sensitivity).toInt() + s).coerceIn(-32768, 32767)
                val magnitude = abs(value)
                if (magnitude > peak) peak = magnitude
                mixed[i * 2] = (value and 0xFF).toByte()
                mixed[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            try {
                writer?.write(mixed)
                dataBytes += mixed.size.toLong()
            } catch (e: Exception) {
                android.util.Log.e("MEMENTO", "wav write failed", e)
                running = false
            }
            val normalized = peak / 32768.0
            level = level * 0.75 + normalized * 0.25
        }
    }

    private fun preferredInput(): android.media.AudioDeviceInfo? {
        val preferred = MementoApp.prefs.get("rec_mic")?.substringBefore(" (")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.productName?.toString()?.equals(preferred, ignoreCase = true) == true
        }
    }

    fun pause(): Boolean {
        if (!running) return false
        paused = true
        level = 0.0
        return true
    }

    fun resume(): Boolean {
        if (!running) return false
        paused = false
        return true
    }

    fun stop(): Double {
        if (!running) return seconds()
        running = false
        paused = false
        try { thread?.join(1500) } catch (e: Exception) { }
        thread = null
        try { mic?.stop() } catch (e: Exception) { }
        mic?.release()
        mic = null
        try { system?.stop() } catch (e: Exception) { }
        system?.release()
        system = null
        systemActive = false
        val total = seconds()
        try {
            writer?.let { raf ->
                raf.seek(0)
                raf.write(wavHeader(dataBytes))
                raf.close()
            }
        } catch (e: Exception) {
            android.util.Log.e("MEMENTO", "wav finalize failed", e)
        }
        writer = null
        level = 0.0
        return total
    }

    private fun wavHeader(dataLength: Long): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt((36 + dataLength).toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataLength.toInt())
        return buffer.array()
    }
}
