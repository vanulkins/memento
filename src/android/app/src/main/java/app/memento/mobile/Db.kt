package app.memento.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Real SQLite layer mirroring the desktop schema (subset that backs the UI lists). */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "memento.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS meetings(id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', created_at TEXT, updated_at TEXT, occurred_at TEXT, folder_path TEXT, duration REAL, status TEXT DEFAULT 'done', memory_type TEXT, sensitivity TEXT, summary_template_id TEXT, participants TEXT DEFAULT '[]')")
        db.execSQL("CREATE TABLE IF NOT EXISTS speakers(id INTEGER PRIMARY KEY, display_name TEXT NOT NULL, is_self INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS transcripts(id INTEGER PRIMARY KEY AUTOINCREMENT, meeting_id TEXT, transcript TEXT, timestamp TEXT, audio_start_time REAL, audio_end_time REAL, duration REAL, speaker_id TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS summary_processes(meeting_id TEXT PRIMARY KEY, status TEXT, metadata TEXT, result TEXT, template_id TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS app_settings_kv(key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS collections(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL DEFAULT '', created_at TEXT, kind TEXT DEFAULT 'manual')")
        db.execSQL("CREATE TABLE IF NOT EXISTS collection_meetings(collection_id INTEGER, meeting_id TEXT, PRIMARY KEY(collection_id, meeting_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT, title TEXT, created_at TEXT, updated_at TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, role TEXT, content TEXT, created_at TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS prompt_templates(id TEXT PRIMARY KEY, name TEXT NOT NULL, prompt TEXT NOT NULL, created_at TEXT, updated_at TEXT)")
    }

    override fun onOpen(db: SQLiteDatabase) {
        if (db.isReadOnly) return
        db.execSQL("CREATE TABLE IF NOT EXISTS speakers(id INTEGER PRIMARY KEY, display_name TEXT NOT NULL, is_self INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT, title TEXT, created_at TEXT, updated_at TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, role TEXT, content TEXT, created_at TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS prompt_templates(id TEXT PRIMARY KEY, name TEXT NOT NULL, prompt TEXT NOT NULL, created_at TEXT, updated_at TEXT)")
        ensureColumn(db, "meetings", "sensitivity", "TEXT")
        ensureColumn(db, "meetings", "summary_template_id", "TEXT")
        cleanupSeededChat(db)
    }

    private fun cleanupSeededChat(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM chat_messages WHERE session_id IN (SELECT s.id FROM chat_sessions s WHERE s.title='ку' AND EXISTS (SELECT 1 FROM chat_messages m WHERE m.session_id=s.id AND m.content='В ваших записях ответ не найден.'))",
            emptyArray()
        )
        db.execSQL(
            "DELETE FROM chat_sessions WHERE title='ку' AND NOT EXISTS (SELECT 1 FROM chat_messages m WHERE m.session_id=chat_sessions.id)",
            emptyArray()
        )
    }

    fun latestChatSession(scopeKey: String = "archive"): JSONObject? {
        readableDatabase.rawQuery("SELECT id,scope,title,created_at,updated_at FROM chat_sessions WHERE scope=? ORDER BY id DESC LIMIT 1", arrayOf(scopeKey)).use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            val messages = JSONArray()
            readableDatabase.rawQuery("SELECT role,content FROM chat_messages WHERE session_id=? ORDER BY id", arrayOf(id.toString())).use { m ->
                while (m.moveToNext()) {
                    messages.put(JSONObject().put("role", m.getString(0)).put("content", m.getString(1)))
                }
            }
            return JSONObject()
                .put("id", id)
                .put("scope", c.getString(1))
                .put("title", c.getString(2))
                .put("created_at", c.getString(3))
                .put("updated_at", c.getString(4))
                .put("messages", messages)
        }
    }

    fun ensureSession(scopeKey: String, title: String): Long {
        readableDatabase.rawQuery("SELECT id FROM chat_sessions WHERE scope=? ORDER BY id DESC LIMIT 1", arrayOf(scopeKey)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        val now = java.time.Instant.now().toString()
        writableDatabase.execSQL(
            "INSERT INTO chat_sessions(scope,title,created_at,updated_at) VALUES(?,?,?,?)",
            arrayOf(scopeKey, title, now, now)
        )
        return readableDatabase.rawQuery("SELECT last_insert_rowid()", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS speakers(id INTEGER PRIMARY KEY, display_name TEXT NOT NULL, is_self INTEGER NOT NULL DEFAULT 0)")
        }
        if (o < 3) {
            ensureColumn(db, "meetings", "sensitivity", "TEXT")
            ensureColumn(db, "meetings", "summary_template_id", "TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS prompt_templates(id TEXT PRIMARY KEY, name TEXT NOT NULL, prompt TEXT NOT NULL, created_at TEXT, updated_at TEXT)")
        }
    }

    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, definition: String) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            var found = false
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) found = true
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }

    fun seedDemoIfNeeded(ctx: android.content.Context) {
        val sp = ctx.getSharedPreferences("memento_store", android.content.Context.MODE_PRIVATE)
        try {
            val raw = ctx.assets.open("demo/demo_meetings.json").readBytes().toString(Charsets.UTF_8)
            val arr = JSONArray(raw)
            // The desktop build keeps one speaker row per voice and numeric transcript.speaker_id.
            // Register the demo voices in the desktop order so the ids (and avatars) match the original build.
            listOf("Аня", "Игорь", "Марина", "Полина", "Алексей", "Лена", "Максим", "Саша", "Катя", "Денис")
                .forEach { ensureSpeaker(it) }
            for (i in 0 until arr.length()) {
                val segs = arr.getJSONObject(i).optJSONArray("segments") ?: continue
                for (j in 0 until segs.length()) {
                    val name = segs.getJSONObject(j).optString("speaker")
                    if (name.isNotBlank()) ensureSpeaker(name)
                }
            }
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val id = m.getString("id")
                val exists = readableDatabase.rawQuery("SELECT 1 FROM meetings WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
                val createdRaw = if (m.isNull("createdAt")) null else m.optString("createdAt")
                val occurredRaw = if (m.isNull("occurredAt")) createdRaw else m.optString("occurredAt")
                if (exists) {
                    if (!createdRaw.isNullOrBlank()) {
                        writableDatabase.execSQL(
                            "UPDATE meetings SET created_at=?, occurred_at=? WHERE id=?",
                            arrayOf(createdRaw, occurredRaw ?: createdRaw, id)
                        )
                    }
                    continue
                }
                val segs = m.optJSONArray("segments") ?: JSONArray()
                val names = LinkedHashSet<String>()
                var end = 0.0
                for (j in 0 until segs.length()) {
                    val s = segs.getJSONObject(j)
                    val name = s.optString("speaker")
                    if (name.isNotBlank()) names.add(name)
                    end = maxOf(end, s.optDouble("end", 0.0))
                }
                val participants = JSONArray()
                names.forEach { participants.put(it) }
                val created = if (m.isNull("createdAt")) java.time.Instant.now().toString() else m.optString("createdAt")
                val occurred = if (m.isNull("occurredAt")) created else m.optString("occurredAt", created)
                writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO meetings(id,title,created_at,updated_at,occurred_at,folder_path,duration,status,participants) VALUES(?,?,?,?,?,?,?,?,?)",
                    arrayOf(id, m.optString("title"), created, created, occurred, null, end, "done", participants.toString())
                )
                writableDatabase.beginTransaction()
                try {
                    for (j in 0 until segs.length()) {
                        val s = segs.getJSONObject(j)
                        val start = s.optDouble("start", 0.0)
                        val stop = s.optDouble("end", start + 5.0)
                        val name = s.optString("speaker")
                        val speakerId = if (name.isBlank()) null else ensureSpeaker(name)
                        writableDatabase.execSQL(
                            "INSERT INTO transcripts(meeting_id,transcript,timestamp,audio_start_time,audio_end_time,duration,speaker_id) VALUES(?,?,?,?,?,?,?)",
                            arrayOf(id, s.optString("text"), created, start, stop, stop - start, speakerId?.toString())
                        )
                    }
                    writableDatabase.setTransactionSuccessful()
                } finally {
                    writableDatabase.endTransaction()
                }
                val summaryRaw = m.optString("summary")
                if (summaryRaw.isNotBlank()) {
                    runCatching { saveSummary(id, JSONObject(summaryRaw)) }
                }
            }
            migrateSpeakerIds()
            sp.edit().putBoolean("demo_seeded", true).apply()
        } catch (e: Throwable) {
            android.util.Log.e("MEMENTO", "demo seed failed", e)
        }
    }

    private fun ensureSpeaker(name: String): Int {
        readableDatabase.rawQuery("SELECT id FROM speakers WHERE display_name=? LIMIT 1", arrayOf(name)).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        val id = readableDatabase.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM speakers", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 1
        }
        writableDatabase.execSQL("INSERT OR IGNORE INTO speakers(id,display_name) VALUES(?,?)", arrayOf(id, name))
        return id
    }

    /** Older installs stored the speaker name in speaker_id; translate it to the desktop's numeric id. */
    fun migrateSpeakerIds() {
        val rows = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT rowid, speaker_id FROM transcripts WHERE speaker_id IS NOT NULL AND speaker_id<>'' AND speaker_id NOT GLOB '[0-9]*'",
            null
        ).use { c ->
            while (c.moveToNext()) rows.add(Pair(c.getLong(0), c.getString(1)))
        }
        if (rows.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            for ((rowid, name) in rows) {
                val legacy = Regex("^S(\\d+)$").find(name)
                val value = if (legacy != null) {
                    (100 + (legacy.groupValues[1].toIntOrNull() ?: 1)).toString()
                } else {
                    ensureSpeaker(name).toString()
                }
                writableDatabase.execSQL("UPDATE transcripts SET speaker_id=? WHERE rowid=?", arrayOf(value, rowid))
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun speakerName(id: Int): String {
        readableDatabase.rawQuery("SELECT display_name FROM speakers WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.trim()
                if (!name.isNullOrBlank() && !isGenericSpeakerName(name)) return name
            }
        }
        return if (id >= 100) "Коллега ${id - 100}" else "Коллега $id"
    }

    private fun isGenericSpeakerName(value: String): Boolean =
        value.matches(Regex("(?iu)^(неизвестн(ый|ая)( коллега)?|unknown( colleague| speaker)?|speaker|спикер)([ _-]*\\d+)?$"))

    private fun cleanTranscriptText(value: String?): String? {
        val text = value.orEmpty()
            .replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty() || text.none { it.isLetterOrDigit() }) return null
        return text
    }

    fun meetings(): JSONArray {
        val a = JSONArray()
        readableDatabase.rawQuery("SELECT id,title,created_at,updated_at,occurred_at,folder_path,duration,status,memory_type,participants FROM meetings ORDER BY COALESCE(occurred_at,created_at) DESC", null).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getString(0))
                o.put("title", c.getString(1) ?: "")
                o.put("created_at", c.getString(2) ?: "")
                o.put("updated_at", c.getString(3) ?: "")
                if (!c.isNull(4)) o.put("occurred_at", c.getString(4))
                if (!c.isNull(5)) o.put("folder_path", c.getString(5))
                o.put("duration_seconds", if (c.isNull(6)) 0 else c.getDouble(6))
                o.put("status", c.getString(7) ?: "done")
                if (!c.isNull(8)) o.put("memory_type", c.getString(8))
                o.put("participants", runCatching { JSONArray(c.getString(9) ?: "[]") }.getOrElse { JSONArray() })
                a.put(o)
            }
        }
        return a
    }
    fun createMeeting(id: String, title: String, folder: String) {
        val now = java.time.Instant.now().toString()
        writableDatabase.execSQL("INSERT OR REPLACE INTO meetings(id,title,created_at,updated_at,occurred_at,folder_path,status) VALUES(?,?,?,?,?,?,?)",
            arrayOf(id, title, now, now, now, folder, "recording"))
    }
    fun saveMeeting(id: String, title: String, audioPath: String, durationSec: Double, transcript: String) {
        val now = java.time.Instant.now().toString()
        writableDatabase.execSQL("UPDATE meetings SET title=?, updated_at=?, folder_path=?, duration=?, status='done' WHERE id=?", arrayOf(title, now, audioPath, durationSec, id))
        cleanTranscriptText(transcript)?.let { cleaned ->
            writableDatabase.execSQL("INSERT INTO transcripts(meeting_id,transcript,timestamp,audio_start_time,audio_end_time,duration) VALUES(?,?,?,?,?,?)",
                arrayOf(id, cleaned, now, 0.0, durationSec, durationSec))
        }
    }

    fun setMeetingStatus(id: String, status: String) {
        val safe = when (status) {
            "recording", "processing", "done" -> status
            else -> "done"
        }
        writableDatabase.execSQL(
            "UPDATE meetings SET status=?, updated_at=? WHERE id=?",
            arrayOf(safe, java.time.Instant.now().toString(), id)
        )
    }
    fun replaceTranscripts(meetingId: String, segments: List<Triple<Double, Double, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transcripts", "meeting_id=?", arrayOf(meetingId))
            val now = java.time.Instant.now().toString()
            for ((start, end, text) in segments) {
                val cleaned = cleanTranscriptText(text) ?: continue
                if (!start.isFinite() || !end.isFinite() || end <= start) continue
                db.execSQL(
                    "INSERT INTO transcripts(meeting_id,transcript,timestamp,audio_start_time,audio_end_time,duration) VALUES(?,?,?,?,?,?)",
                    arrayOf(meetingId, cleaned, now, start, end, end - start)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun transcriptSegments(meetingId: String): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery(
            "SELECT rowid, transcript, audio_start_time, audio_end_time, speaker_id FROM transcripts WHERE meeting_id=? ORDER BY audio_start_time",
            arrayOf(meetingId)
        ).use { c ->
            while (c.moveToNext()) {
                val text = cleanTranscriptText(c.getString(1)) ?: continue
                val raw = if (c.isNull(4)) null else c.getString(4)
                // A transcript without diarization is still a statement by a visible,
                // deterministic participant. This also prevents blank speaker headers.
                val sid = raw?.toIntOrNull() ?: 101
                arr.put(JSONObject()
                    .put("id", c.getLong(0))
                    .put("transcript", text)
                    .put("text", text)
                    .put("sequence_id", c.getLong(0))
                    .put("display_time", String.format(java.util.Locale.US, "%02d:%02d", (c.getDouble(2) / 60).toInt(), (c.getDouble(2) % 60).toInt()))
                    .put("confidence", 1.0)
                    .put("is_partial", false)
                    .put("tokens", JSONArray())
                    .put("audio_start_time", c.getDouble(2))
                    .put("audio_end_time", c.getDouble(3))
                    .put("duration", c.getDouble(3) - c.getDouble(2))
                    .put("speaker_id", sid)
                    .put("speaker", speakerName(sid)))
            }
        }
        return arr
    }

    fun speakers(meetingId: String): JSONArray {
        val arr = JSONArray()
        val rows = LinkedHashMap<Int, Double>()
        readableDatabase.rawQuery(
            "SELECT speaker_id, MIN(audio_start_time) FROM transcripts WHERE meeting_id=? AND speaker_id IS NOT NULL AND speaker_id<>'' GROUP BY speaker_id ORDER BY MIN(audio_start_time)",
            arrayOf(meetingId)
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)?.toIntOrNull() ?: continue
                rows[id] = c.getDouble(1)
            }
        }
        if (rows.isEmpty() && transcriptSegments(meetingId).length() > 0) rows[101] = 0.0
        for (id in rows.keys) {
            arr.put(JSONObject()
                .put("id", id)
                .put("display_name", speakerName(id))
                .put("is_self", false)
                .put("aliases", JSONArray()))
        }
        return arr
    }

    /** Every visible transcript row must have a stable, human-readable participant. */
    fun ensureSpeakerAssignments(meetingId: String) {
        val count = transcriptCount(meetingId)
        if (count == 0) return
        writableDatabase.execSQL(
            "UPDATE transcripts SET speaker_id='101' WHERE meeting_id=? AND (speaker_id IS NULL OR speaker_id='')",
            arrayOf(meetingId)
        )
        rebuildParticipants(meetingId)
    }

    fun participantNames(meetingId: String): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery(
            "SELECT DISTINCT speaker_id, MIN(audio_start_time) first_time FROM transcripts " +
                "WHERE meeting_id=? AND speaker_id IS NOT NULL AND speaker_id<>'' " +
                "GROUP BY speaker_id ORDER BY first_time, CAST(speaker_id AS INTEGER)",
            arrayOf(meetingId)
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)?.toIntOrNull() ?: continue
                result.put(speakerName(id))
            }
        }
        if (result.length() == 0 && transcriptCount(meetingId) > 0) result.put("Коллега 1")
        return result
    }

    private fun rebuildParticipants(meetingId: String) {
        val participants = participantNames(meetingId)
        writableDatabase.execSQL(
            "UPDATE meetings SET participants=?,updated_at=? WHERE id=?",
            arrayOf(participants.toString(), java.time.Instant.now().toString(), meetingId)
        )
    }

    fun transcriptCount(meetingId: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM transcripts WHERE meeting_id=?", arrayOf(meetingId)).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun assignedCount(meetingId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM transcripts WHERE meeting_id=? AND speaker_id IS NOT NULL AND speaker_id<>''",
            arrayOf(meetingId)
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun applyDiarization(meetingId: String, turns: List<Triple<Double, Double, Int>>) {
        if (turns.isEmpty()) return
        data class TranscriptRow(val rowId: Long, val text: String, val start: Double, val end: Double)
        data class Slice(var start: Double, var end: Double, val speaker: Int)
        val rows = ArrayList<TranscriptRow>()
        readableDatabase.rawQuery(
            "SELECT rowid, transcript, audio_start_time, audio_end_time FROM transcripts WHERE meeting_id=? ORDER BY audio_start_time",
            arrayOf(meetingId)
        ).use { c ->
            while (c.moveToNext()) {
                cleanTranscriptText(c.getString(1))?.let {
                    rows.add(TranscriptRow(c.getLong(0), it, c.getDouble(2), c.getDouble(3)))
                }
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (row in rows) {
                val start = row.start
                val end = row.end
                val slices = ArrayList<Slice>()
                for (turn in turns.sortedBy { it.first }) {
                    val sliceStart = maxOf(start, turn.first)
                    val sliceEnd = minOf(end, turn.second)
                    if (sliceEnd - sliceStart < 0.12) continue
                    val previous = slices.lastOrNull()
                    if (previous != null && previous.speaker == turn.third && sliceStart - previous.end <= 0.35) {
                        previous.end = sliceEnd
                    } else slices.add(Slice(sliceStart, sliceEnd, turn.third))
                }

                val speakers = slices.map { it.speaker }.distinct()
                val words = row.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (speakers.size > 1 && words.size >= slices.size) {
                    // ASR timestamps are utterance-level. Split the text proportionally
                    // at diarization boundaries so a speaker change inside one long ASR
                    // segment becomes two visible transcript statements.
                    db.delete("transcripts", "rowid=?", arrayOf(row.rowId.toString()))
                    val totalDuration = slices.sumOf { it.end - it.start }.coerceAtLeast(0.001)
                    var wordIndex = 0
                    var elapsed = 0.0
                    for (i in slices.indices) {
                        val slice = slices[i]
                        elapsed += slice.end - slice.start
                        val remainingSlices = slices.size - i - 1
                        val target = if (i == slices.lastIndex) words.size else
                            ((elapsed / totalDuration) * words.size).toInt()
                                .coerceIn(wordIndex + 1, words.size - remainingSlices)
                        val text = cleanTranscriptText(words.subList(wordIndex, target).joinToString(" "))
                        if (text != null) {
                            db.execSQL(
                                "INSERT INTO transcripts(meeting_id,transcript,timestamp,audio_start_time,audio_end_time,duration,speaker_id) VALUES(?,?,?,?,?,?,?)",
                                arrayOf(meetingId, text, java.time.Instant.now().toString(), slice.start, slice.end,
                                    slice.end - slice.start, (101 + slice.speaker).toString())
                            )
                        }
                        wordIndex = target
                    }
                    continue
                }

                var chosen = -1
                var bestOverlap = 0.0
                for (t in turns) {
                    val overlap = minOf(end, t.second) - maxOf(start, t.first)
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap
                        chosen = t.third
                    }
                }
                if (chosen < 0 || bestOverlap < 0.01) {
                    var bestDistance = Double.MAX_VALUE
                    for (t in turns) {
                        val distance = when {
                            end < t.first -> t.first - end
                            start > t.second -> start - t.second
                            else -> 0.0
                        }
                        if (distance < bestDistance) {
                            bestDistance = distance
                            chosen = t.third
                        }
                    }
                }
                if (chosen < 0) continue
                db.execSQL("UPDATE transcripts SET speaker_id=? WHERE rowid=?", arrayOf((101 + chosen).toString(), row.rowId))
            }
            mergeAdjacentSameSpeaker(db, meetingId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ensureSpeakerAssignments(meetingId)
    }

    /** Keep ASR sentence boundaries internally, but do not show several bubbles for one
     * uninterrupted turn by the same participant. */
    private fun mergeAdjacentSameSpeaker(db: android.database.sqlite.SQLiteDatabase, meetingId: String) {
        data class Row(val id: Long, val text: String, val start: Double, val end: Double, val speaker: String)
        val rows = ArrayList<Row>()
        db.rawQuery(
            "SELECT rowid,transcript,audio_start_time,audio_end_time,speaker_id FROM transcripts WHERE meeting_id=? ORDER BY audio_start_time,rowid",
            arrayOf(meetingId)
        ).use { c ->
            while (c.moveToNext()) {
                val text = cleanTranscriptText(c.getString(1)) ?: continue
                val speaker = if (c.isNull(4)) "" else c.getString(4).orEmpty()
                rows.add(Row(c.getLong(0), text, c.getDouble(2), c.getDouble(3), speaker))
            }
        }
        if (rows.size < 2) return
        var current = rows[0]
        for (i in 1 until rows.size) {
            val next = rows[i]
            val gap = next.start - current.end
            if (current.speaker.isNotBlank() && current.speaker == next.speaker && gap <= 1.25) {
                val joined = (current.text.trimEnd() + " " + next.text.trimStart()).trim()
                db.execSQL(
                    "UPDATE transcripts SET transcript=?,audio_end_time=?,duration=? WHERE rowid=?",
                    arrayOf(joined, next.end, next.end - current.start, current.id)
                )
                db.delete("transcripts", "rowid=?", arrayOf(next.id.toString()))
                current = current.copy(text = joined, end = next.end)
            } else current = next
        }
    }

    fun transcript(meetingId: String): String {
        readableDatabase.rawQuery("SELECT transcript FROM transcripts WHERE meeting_id=? ORDER BY audio_start_time", arrayOf(meetingId)).use { c ->
            val result = ArrayList<String>()
            while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { result.add(it) }
            return result.joinToString("\n")
        }
    }

    fun aiContext(meetingId: String?, maxChars: Int = 180_000): String {
        val output = StringBuilder()
        val selection = if (meetingId.isNullOrBlank()) "" else "WHERE t.meeting_id=?"
        val params = if (meetingId.isNullOrBlank()) null else arrayOf(meetingId)
        readableDatabase.rawQuery(
            "SELECT m.title,t.meeting_id,t.speaker_id,t.audio_start_time,t.transcript " +
                "FROM transcripts t JOIN meetings m ON m.id=t.meeting_id $selection " +
                "ORDER BY COALESCE(m.occurred_at,m.created_at) DESC,t.audio_start_time LIMIT 3000",
            params
        ).use { c ->
            var previous = ""
            while (c.moveToNext() && output.length < maxChars) {
                val id = c.getString(1)
                if (id != previous) {
                    if (output.isNotEmpty()) output.append("\n")
                    output.append("ВСТРЕЧА: ").append(c.getString(0)).append(" [").append(id).append("]\n")
                    previous = id
                }
                val speaker = c.getString(2)?.toIntOrNull()?.let { "Коллега ${(it - 100).coerceAtLeast(1)}" } ?: "Коллега"
                output.append('[').append(clockForAi(c.getDouble(3))).append("] ")
                    .append(speaker).append(": ").append(c.getString(4)).append('\n')
            }
        }
        return output.toString().take(maxChars)
    }

    private fun clockForAi(seconds: Double): String {
        val value = seconds.toInt().coerceAtLeast(0)
        return "%02d:%02d".format(value / 60, value % 60)
    }
    fun audioPath(meetingId: String): String? {
        readableDatabase.rawQuery("SELECT folder_path FROM meetings WHERE id=?", arrayOf(meetingId)).use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }
    fun clearAudioPath(meetingId: String) {
        writableDatabase.execSQL("UPDATE meetings SET folder_path=NULL WHERE id=?", arrayOf(meetingId))
    }

    fun applyGeneratedTitle(meetingId: String, title: String, force: Boolean = false) {
        if (title.isBlank() || isFixtureMeeting(meetingId)) return
        val current = readableDatabase.rawQuery("SELECT title FROM meetings WHERE id=?", arrayOf(meetingId)).use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() else ""
        }
        val generated = current.isBlank() || Regex(
            "(?iu)^(meeting|auto meeting|встреча|утренн(ий|яя).*|дневн(ой|ая).*|вечерн(ий|яя).*|ночн(ой|ая).*)[ _:-]?.*"
        ).matches(current.trim())
        if (force || generated) writableDatabase.execSQL("UPDATE meetings SET title=?,updated_at=? WHERE id=?",
            arrayOf(title.take(80), java.time.Instant.now().toString(), meetingId))
    }

    fun searchMeetings(query: String, filters: JSONObject?, limit: Int): JSONArray {
        val out = JSONArray()
        val terms = query.trim().lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }.distinct().take(8)
        val params = ArrayList<String>()
        val conditions = ArrayList<String>()
        for (term in terms) {
            conditions.add("(lower(m.title) LIKE ? OR EXISTS (SELECT 1 FROM transcripts t WHERE t.meeting_id=m.id AND lower(t.transcript) LIKE ?))")
            params.add("%$term%")
            params.add("%$term%")
        }
        filters?.optString("date_from")?.takeIf { it.isNotBlank() }?.let {
            conditions.add("COALESCE(m.occurred_at,m.created_at)>=?"); params.add(it)
        }
        filters?.optString("date_to")?.takeIf { it.isNotBlank() }?.let {
            conditions.add("COALESCE(m.occurred_at,m.created_at)<=?"); params.add(it)
        }
        val collections = filters?.optJSONArray("collection_ids")
        if (collections != null && collections.length() > 0) {
            conditions.add("EXISTS (SELECT 1 FROM collection_meetings cm WHERE cm.meeting_id=m.id AND cm.collection_id=?)")
            params.add(collections.optLong(0).toString())
        }
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val max = limit.coerceIn(1, 100)
        readableDatabase.rawQuery(
            "SELECT m.id,m.title,m.created_at,m.updated_at,m.occurred_at,m.folder_path,m.duration,m.status,m.memory_type,m.participants FROM meetings m$where ORDER BY COALESCE(m.occurred_at,m.created_at) DESC LIMIT $max",
            params.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) out.put(JSONObject()
                .put("id", c.getString(0)).put("title", c.getString(1) ?: "")
                .put("created_at", c.getString(2) ?: "").put("updated_at", c.getString(3) ?: "")
                .put("occurred_at", if (c.isNull(4)) JSONObject.NULL else c.getString(4))
                .put("folder_path", if (c.isNull(5)) JSONObject.NULL else c.getString(5))
                .put("duration_seconds", if (c.isNull(6)) 0.0 else c.getDouble(6))
                .put("status", c.getString(7) ?: "done")
                .put("memory_type", if (c.isNull(8)) JSONObject.NULL else c.getString(8))
                .put("participants", JSONArray(c.getString(9) ?: "[]")))
        }
        return out
    }
    fun renameMeeting(id: String, title: String) {
        writableDatabase.execSQL("UPDATE meetings SET title=?, updated_at=? WHERE id=?", arrayOf(title, java.time.Instant.now().toString(), id))
    }

    fun meetingMemoryConfig(id: String): JSONObject {
        readableDatabase.rawQuery(
            "SELECT memory_type,sensitivity,summary_template_id FROM meetings WHERE id=?",
            arrayOf(id)
        ).use { c ->
            if (c.moveToFirst()) {
                val memory = if (c.isNull(0)) "general" else c.getString(0)
                val sensitivity = if (c.isNull(1)) "standard" else c.getString(1)
                val template = if (c.isNull(2)) {
                    when (memory) {
                        "standup" -> "daily_standup"
                        "interview" -> "interview_memory"
                        else -> "standard_meeting"
                    }
                } else c.getString(2)
                return JSONObject()
                    .put("meeting_id", id)
                    .put("memory_type", memory)
                    .put("sensitivity", sensitivity)
                    .put("summary_template_id", template)
            }
        }
        return JSONObject()
            .put("meeting_id", id)
            .put("memory_type", "general")
            .put("sensitivity", "standard")
            .put("summary_template_id", "standard_meeting")
    }

    fun setMeetingMemoryConfig(id: String, memoryType: String, sensitivity: String, templateId: String): JSONObject {
        writableDatabase.execSQL(
            "UPDATE meetings SET memory_type=?,sensitivity=?,summary_template_id=?,updated_at=? WHERE id=?",
            arrayOf(memoryType, sensitivity, templateId, java.time.Instant.now().toString(), id)
        )
        return meetingMemoryConfig(id)
    }

    fun listPromptTemplates(): JSONArray {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT id,name,prompt,created_at,updated_at FROM prompt_templates ORDER BY created_at", null).use { c ->
            while (c.moveToNext()) result.put(JSONObject()
                .put("id", c.getString(0))
                .put("name", c.getString(1))
                .put("prompt", c.getString(2))
                .put("created_at", c.getString(3))
                .put("updated_at", c.getString(4)))
        }
        return result
    }

    fun promptTemplate(id: String): JSONObject? {
        readableDatabase.rawQuery("SELECT id,name,prompt,created_at,updated_at FROM prompt_templates WHERE id=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            return JSONObject().put("id", c.getString(0)).put("name", c.getString(1)).put("prompt", c.getString(2))
                .put("created_at", c.getString(3)).put("updated_at", c.getString(4))
        }
    }

    fun createPromptTemplate(name: String, prompt: String): JSONObject {
        val id = "custom_prompt_" + java.util.UUID.randomUUID().toString()
        val now = java.time.Instant.now().toString()
        writableDatabase.execSQL(
            "INSERT INTO prompt_templates(id,name,prompt,created_at,updated_at) VALUES(?,?,?,?,?)",
            arrayOf(id, name, prompt, now, now)
        )
        return promptTemplate(id)!!
    }

    fun updatePromptTemplate(id: String, name: String, prompt: String): JSONObject {
        writableDatabase.execSQL(
            "UPDATE prompt_templates SET name=?,prompt=?,updated_at=? WHERE id=?",
            arrayOf(name, prompt, java.time.Instant.now().toString(), id)
        )
        return promptTemplate(id) ?: throw IllegalArgumentException("Шаблон не найден")
    }

    fun deletePromptTemplate(id: String) {
        writableDatabase.execSQL("DELETE FROM prompt_templates WHERE id=?", arrayOf(id))
    }

    fun assignSpeaker(meetingId: String, transcriptId: Long, speakerId: Int?) {
        writableDatabase.execSQL(
            "UPDATE transcripts SET speaker_id=? WHERE meeting_id=? AND rowid=?",
            arrayOf(speakerId?.toString(), meetingId, transcriptId)
        )
    }

    fun addSpeaker(displayName: String): Int = ensureSpeaker(displayName.trim())

    fun renameSpeaker(id: Int, displayName: String) {
        writableDatabase.execSQL("UPDATE speakers SET display_name=? WHERE id=?", arrayOf(displayName.trim(), id))
    }

    fun setSelfSpeaker(id: Int, isSelf: Boolean) {
        if (isSelf) writableDatabase.execSQL("UPDATE speakers SET is_self=0")
        writableDatabase.execSQL("UPDATE speakers SET is_self=? WHERE id=?", arrayOf(if (isSelf) 1 else 0, id))
    }

    fun correctTranscript(id: Long, text: String) {
        writableDatabase.execSQL("UPDATE transcripts SET transcript=? WHERE rowid=?", arrayOf(text, id))
    }

    fun listCollections(): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery("SELECT id,title,created_at,kind FROM collections ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1) ?: ""
                arr.put(
                    JSONObject()
                        .put("id", c.getLong(0))
                        .put("title", title)
                        .put("name", title)
                        .put("created_at", c.getString(2) ?: "")
                        .put("kind", c.getString(3) ?: "manual")
                )
            }
        }
        return arr
    }

    fun createCollection(title: String, kind: String): Long {
        writableDatabase.execSQL(
            "INSERT INTO collections(title,created_at,kind) VALUES(?,?,?)",
            arrayOf(title, java.time.Instant.now().toString(), kind)
        )
        readableDatabase.rawQuery("SELECT last_insert_rowid()", null).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0L
    }

    fun renameCollection(id: Long, title: String) {
        writableDatabase.execSQL("UPDATE collections SET title=? WHERE id=?", arrayOf(title, id))
    }

    fun deleteCollection(id: Long) {
        writableDatabase.execSQL("DELETE FROM collections WHERE id=?", arrayOf(id))
        writableDatabase.execSQL("DELETE FROM collection_meetings WHERE collection_id=?", arrayOf(id))
    }

    fun setCollectionMeetings(id: Long, meetingIds: List<String>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("DELETE FROM collection_meetings WHERE collection_id=?", arrayOf(id))
            for (meetingId in meetingIds) {
                writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO collection_meetings(collection_id,meeting_id) VALUES(?,?)",
                    arrayOf(id, meetingId)
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun summary(meetingId: String): JSONObject? {
        readableDatabase.rawQuery("SELECT result FROM summary_processes WHERE meeting_id=?", arrayOf(meetingId)).use { c ->
            if (c.moveToFirst()) {
                val raw = c.getString(0)
                if (!raw.isNullOrBlank()) {
                    return try {
                        JSONObject(raw).put("participants", participantNames(meetingId))
                    } catch (e: Exception) { null }
                }
            }
        }
        return null
    }

    fun isFixtureMeeting(meetingId: String): Boolean = meetingId.startsWith("meeting-example-")

    fun saveSummary(meetingId: String, result: JSONObject) {
        // Demo summaries are authored fixtures from the original application. A
        // meeting-type change may request processing, but must never replace them.
        if (isFixtureMeeting(meetingId) && summary(meetingId) != null) return
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO summary_processes(meeting_id,status,metadata,result,template_id) VALUES(?,?,?,?,?)",
            arrayOf(meetingId, "completed", "{}", result.toString(), "standard_meeting")
        )
    }

    fun deleteMeeting(id: String) {
        writableDatabase.execSQL("DELETE FROM meetings WHERE id=?", arrayOf(id))
        writableDatabase.execSQL("DELETE FROM transcripts WHERE meeting_id=?", arrayOf(id))
        writableDatabase.execSQL("DELETE FROM summary_processes WHERE meeting_id=?", arrayOf(id))
    }
    fun setKV(k: String, v: String) = writableDatabase.execSQL("INSERT OR REPLACE INTO app_settings_kv(key,value) VALUES(?,?)", arrayOf(k, v))
    fun getKV(k: String): String? {
        readableDatabase.rawQuery("SELECT value FROM app_settings_kv WHERE key=?", arrayOf(k)).use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    fun ensureKnowledgeBaseSession(): Long {
        readableDatabase.rawQuery("SELECT id FROM chat_sessions WHERE scope='archive' ORDER BY id DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        val now = java.time.Instant.now().toString()
        writableDatabase.execSQL(
            "INSERT INTO chat_sessions(scope,title,created_at,updated_at) VALUES(?,?,?,?)",
            arrayOf("archive", "База знаний", now, now)
        )
        val id = readableDatabase.rawQuery("SELECT last_insert_rowid()", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        appendChatMessage(
            id,
            "assistant",
            "Здесь можно спросить о своих встречах: о чём договорились, кто что обещал, какие были сроки. Поиск идёт по расшифровкам на устройстве."
        )
        return id
    }

    fun appendChatMessage(sessionId: Long, role: String, content: String) {
        val now = java.time.Instant.now().toString()
        writableDatabase.execSQL(
            "INSERT INTO chat_messages(session_id,role,content,created_at) VALUES(?,?,?,?)",
            arrayOf(sessionId, role, content, now)
        )
        writableDatabase.execSQL("UPDATE chat_sessions SET updated_at=? WHERE id=?", arrayOf(now, sessionId))
    }

    fun searchTranscripts(query: String, meetingId: String?, limit: Int): JSONArray {
        val out = JSONArray()
        val terms = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .distinct()
            .take(6)
        if (terms.isEmpty()) return out
        val params = ArrayList<String>()
        val cond = terms.joinToString(" AND ") { "lower(t.transcript) LIKE ?" }
        terms.forEach { params.add("%$it%") }
        var filter = ""
        if (!meetingId.isNullOrBlank()) {
            filter = " AND t.meeting_id=?"
            params.add(meetingId)
        }
        val sql = "SELECT t.meeting_id, m.title, t.transcript, t.audio_start_time FROM transcripts t " +
            "JOIN meetings m ON m.id=t.meeting_id WHERE $cond$filter " +
            "ORDER BY COALESCE(m.occurred_at, m.created_at) DESC LIMIT $limit"
        readableDatabase.rawQuery(sql, params.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.put(
                    JSONObject()
                        .put("meeting_id", c.getString(0))
                        .put("meeting_title", c.getString(1) ?: "")
                        .put("text", c.getString(2) ?: "")
                        .put("start_seconds", c.getDouble(3))
                )
            }
        }
        return out
    }
}
