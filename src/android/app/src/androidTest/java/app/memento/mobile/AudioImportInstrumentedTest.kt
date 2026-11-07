package app.memento.mobile

import android.content.Intent
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class AudioImportInstrumentedTest {
    @Test
    fun mp3IsInspectedAndNormalizedTo16kMonoWav() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "import-test.mp3")
        val target = File(context.cacheDir, "import-test.wav")
        source.writeBytes(Base64.decode(MP3, Base64.DEFAULT))

        val inspected = AudioDecoder.inspect(source)
        assertTrue(inspected.mime.contains("mpeg") || inspected.mime.contains("mp3"))
        assertTrue(inspected.durationSeconds > 0.2)

        val normalized = AudioDecoder.normalize(source, target)
        assertEquals(16000, normalized.sampleRate)
        assertEquals(1, normalized.channels)
        assertTrue(target.length() > 44)
        assertEquals("RIFF", target.inputStream().use { input ->
            val header = ByteArray(4)
            assertEquals(4, input.read(header))
            String(header, Charsets.US_ASCII)
        })
    }

    @Test
    fun diarizationSeparatesMultipleVoices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "diar-test.wav")
        assertTrue("Push diar-test.wav into target files before this diagnostic test", source.isFile)
        assertTrue("Diarization model is not installed", MementoApp.diarization.ready())
        val turns = MementoApp.diarization.diarize(source) { }
        val speakers = turns.map { it.speaker }.distinct()
        android.util.Log.i("MEMENTO_TEST", "turns=$turns speakers=$speakers")
        assertTrue("Expected at least three voices, got $speakers", speakers.size >= 3)
    }

    @Test
    fun devicePickerPathCanBeImportedAfterTheOriginalIsRemoved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.filesDir, "imports/device-picker-test.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(Base64.decode(MP3, Base64.DEFAULT))
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            val bridge = Bridge(activity)
            val resolved = bridge.sourceFile(JSONObject().put("sourcePath", source.absolutePath))
            assertNotNull(resolved)
            val durable = bridge.durableImportSource(resolved!!)
            assertTrue(durable.isFile)
            assertEquals(source.length(), durable.length())

            // The bridge makes its durable job copy before returning, so the picker
            // cache may disappear immediately without breaking the background import.
            assertTrue(source.delete())
            assertFalse(source.exists())
            assertTrue(durable.isFile)
            assertTrue(AudioDecoder.inspect(durable).durationSeconds > 0.2)
            assertTrue(durable.delete())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun transcriptionVariantSwitchesWithoutASecondModelDownload() {
        val models = MementoApp.models
        val original = models.selected()
        val cpu = models.variant("e2e-rnnt-int8-cpu")!!
        val neural = models.variant("e2e-rnnt-int8-nnapi")!!
        try {
            assertEquals(cpu.dir, neural.dir)
            assertEquals(models.variantDir(cpu).canonicalPath, models.variantDir(neural).canonicalPath)
            models.select(neural.id)
            assertEquals(neural.id, models.statusJson().getString("selected"))
            models.select(cpu.id)
            assertEquals(cpu.id, models.statusJson().getString("selected"))
        } finally {
            models.select(original)
        }
    }

    companion object {
        private const val MP3 = "SUQzBAAAAAAAI1RTU0UAAAAPAAADTGF2ZjYyLjEyLjEwMgAAAAAAAAAAAAAA//NYwAAAAAAAAAAAAEluZm8AAAAPAAAADAAABBQAPT09PT09PT1PT09PT09PT2FhYWFhYWFhcnJycnJycnJyhISEhISEhISWlpaWlpaWlqenp6enp6enp7m5ubm5ubm5y8vLy8vLy8vc3Nzc3Nzc3Nzu7u7u7u7u7v//////////AAAAAExhdmM2Mi4yOAAAAAAAAAAAAAAAACQC4AAAAAAAAAQUZb1nRAAAAAAAAAAAAAAA//MoxAALwALNv0EYAqkAZLh/+AAD4Pg+D58EIPg+CDjkuD4Pg/wQdg+f/5QP8HDmIAf1g4cyAP8CO5/oaUoF//4NAE9feP39//MoxAwQMOaIAZuQADCi84RmBhkcYogUUPHFQHyDeEPhAcYt6ADqHcxAhzhzij/kVIqZF4vf+iiYiI99YKiI9/BVhAAJBG/5//MoxAYMkGJiGd0IAv/6yy3yGlpKSBgCmDQ3mnwqHDI8GJYoGFQNmCQdkoFqwy24eJ//7/9/v//71SAABA4IYGB///5Yxp3V//MoxA4QIGo9vt+0gIVKoQAYNFDBkEzPuMZijozhRPjAmA1BxY6G8yZsOPL1gadtDPu+v/Gfz37f6qqv///+5SAABJBEJAB8//MoxAgOaGJZvg9ySsA1diDS00AqBZgYJhlj8RsoOBg2CphhmlMX+ZvOWwz61O2PXpd/Iet3//r+j2oT91NaqAAAFtsEFnBH//MoxAkMCF5qXgm4Qs5lSh8IYuRBADVITggYH0qyARJ/UNwW+/07Jj9n7P/+z///V/+j9NUBQAWJANY00ahlwlVQuGMVJMtf//MoxBMNGGJYdga6KlTZ4ZwEBzDTA0Ji9jJZdZDi/v///9//+p3/6XffR2UZ7QqAAAACSQMCAH+nWm4AXmIgCAIcmNGQmfYq//MoxBkMwF5iXg9wSpgQBBeA0vVK3k+Zd/q0bP2q///6P///+vZpwAAAAoGAp6ggs7I28V+HEHaOatIad9guYcAQYAAcFRdH//MoxCEMmGJV/hZ6RAD3gobgt//////+zd/////zlf///6kYjL+qBgUAEgAaMDYFYwrBQDGiUmM10HkwbwOTARAxMBkG8AgN//MoxCkMyG5MAV4QAZgNgCp1wfLKSxIqAXI5QpRwQAH/RPuwFlvxmB6VZSE0LhD+hTBvzczDEFTQJwDOClkQeYNsGQDkhbhS//MoxDAYIRaU9ZqYAD6RMGihAYaQpEcr5cNEDBA+Q0vHyZ/UgykGScxPHeCAAOAkVYd8oUOBgoVnSv/86VoJQcv3DEktmWBg//MoxAoLKE5UP8YYAhInEiVeSJHQVGB0FR4arO8S3buJf/7v8792d/rO///8ReDNTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVV"
    }
}
