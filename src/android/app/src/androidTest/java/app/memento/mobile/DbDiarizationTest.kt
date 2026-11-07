package app.memento.mobile

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DbDiarizationTest {
    @Test
    fun speakerBoundaryInsideAsrUtteranceCreatesSeparateStatements() {
        val db = Db(ApplicationProvider.getApplicationContext())
        val id = "diarization-test-${System.nanoTime()}"
        try {
            db.replaceTranscripts(
                id,
                listOf(Triple(0.0, 10.0, "раз два три четыре пять шесть семь восемь девять десять"))
            )
            db.applyDiarization(
                id,
                listOf(Triple(0.0, 4.0, 0), Triple(4.0, 10.0, 1))
            )

            val segments = db.transcriptSegments(id)
            assertEquals(2, segments.length())
            assertEquals(101, segments.getJSONObject(0).getInt("speaker_id"))
            assertEquals(102, segments.getJSONObject(1).getInt("speaker_id"))
            val reconstructed = (0 until segments.length())
                .joinToString(" ") { segments.getJSONObject(it).getString("text") }
            assertEquals("раз два три четыре пять шесть семь восемь девять десять", reconstructed)
            assertTrue(segments.getJSONObject(0).getDouble("audio_end_time") <=
                segments.getJSONObject(1).getDouble("audio_start_time"))
        } finally {
            db.deleteMeeting(id)
            db.close()
        }
    }
}
