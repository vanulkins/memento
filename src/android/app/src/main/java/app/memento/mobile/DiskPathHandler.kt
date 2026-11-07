package app.memento.mobile

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class DiskPathHandler(
    private val rootDir: File,
    private val fileRoots: List<File>
) {

    fun respond(uri: Uri, headers: Map<String, String>?): WebResourceResponse {
        val path = uri.path ?: "/"
        if (path == "/__file/" || path == "/__file") {
            return serveAbsolute(uri.getQueryParameter("p"), headers)
        }
        val rel = normalize(path)
        for (candidate in candidates(rel)) {
            val file = File(rootDir, candidate)
            if (file.isFile && file.canRead()) return serveAsset(file, candidate)
        }
        if (rel.isNotEmpty() && !rel.contains('.')) {
            val index = File(rootDir, "index.html")
            if (index.isFile) return serveAsset(index, "index.html")
        }
        android.util.Log.w("MEMENTO", "asset not found: $path")
        return notFound()
    }

    private fun normalize(path: String): String {
        var p = path
        val q = p.indexOf('?')
        if (q >= 0) p = p.substring(0, q)
        val h = p.indexOf('#')
        if (h >= 0) p = p.substring(0, h)
        while (p.startsWith("/")) p = p.substring(1)
        if (p.endsWith("/")) p = p.substring(0, p.length - 1)
        return p
    }

    private fun candidates(rel: String): List<String> {
        if (rel.isEmpty()) return listOf("index.html")
        val out = ArrayList<String>()
        out.add(rel)
        if (!rel.contains('.')) {
            out.add("$rel.html")
            out.add("$rel/index.html")
        }
        return out.distinct()
    }

    private fun serveAbsolute(rawPath: String?, headers: Map<String, String>?): WebResourceResponse {
        if (rawPath.isNullOrBlank()) return notFound()
        val file = File(rawPath)
        if (!file.isFile || !file.canRead()) {
            android.util.Log.w("MEMENTO", "file not found: $rawPath")
            return notFound()
        }
        if (!isAllowed(file)) {
            android.util.Log.w("MEMENTO", "file outside app storage: $rawPath")
            return forbidden()
        }
        return serveFile(file, headers)
    }

    private fun isAllowed(file: File): Boolean {
        val canonical = try { file.canonicalFile } catch (e: Exception) { return false }
        for (root in fileRoots) {
            val base = try { root.canonicalFile } catch (e: Exception) { continue }
            if (canonical.path == base.path || canonical.path.startsWith(base.path + File.separator)) return true
        }
        return false
    }

    private fun serveFile(file: File, headers: Map<String, String>?): WebResourceResponse {
        val total = file.length()
        val range = parseRange(header(headers, "Range"), total)
        val mime = mimeType(file.name)
        if (range == null) {
            val response = WebResourceResponse(mime.first, mime.second, FileInputStream(file))
            response.setStatusCodeAndReasonPhrase(200, "OK")
            response.responseHeaders = responseHeaders(mime.first, total, 200, null)
            return response
        }
        val (start, end) = range
        val length = end - start + 1
        val stream = FileInputStream(file)
        stream.channel.position(start)
        val body: InputStream = LimitedInputStream(stream, length)
        val response = WebResourceResponse(mime.first, mime.second, body)
        response.setStatusCodeAndReasonPhrase(206, "Partial Content")
        val contentRange = "bytes $start-$end/$total"
        response.responseHeaders = responseHeaders(mime.first, length, 206, contentRange)
        return response
    }

    private fun serveAsset(file: File, name: String): WebResourceResponse {
        val mime = mimeType(name)
        android.util.Log.i("MEMENTO", "serve $name as ${mime.first} (${file.length()} bytes)")
        val response = WebResourceResponse(mime.first, mime.second, FileInputStream(file))
        response.setStatusCodeAndReasonPhrase(200, "OK")
        response.responseHeaders = responseHeaders(mime.first, file.length(), 200, null)
        return response
    }

    private fun responseHeaders(type: String, length: Long, status: Int, contentRange: String?): Map<String, String> {
        val headers = HashMap<String, String>()
        headers["Content-Type"] = contentTypeHeader(type)
        headers["Content-Length"] = length.toString()
        headers["Accept-Ranges"] = "bytes"
        headers["Cache-Control"] = "no-store"
        headers["Access-Control-Allow-Origin"] = "*"
        if (contentRange != null) headers["Content-Range"] = contentRange
        return headers
    }

    private fun contentTypeHeader(type: String): String {
        val textual = type.startsWith("text/") || type == "application/json" ||
            type == "application/xml" || type == "application/javascript" || type == "image/svg+xml"
        return if (textual) "$type; charset=utf-8" else type
    }

    private fun header(headers: Map<String, String>?, name: String): String? {
        if (headers == null) return null
        for ((key, value) in headers) {
            if (key.equals(name, ignoreCase = true)) return value
        }
        return null
    }

    private fun parseRange(value: String?, total: Long): Pair<Long, Long>? {
        if (value == null || total <= 0) return null
        val trimmed = value.trim()
        if (!trimmed.startsWith("bytes=", ignoreCase = true)) return null
        val spec = trimmed.substring(6).substringBefore(',')
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        return try {
            var start: Long
            var end: Long
            if (startText.isEmpty()) {
                val suffix = endText.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                start = if (suffix >= total) 0 else total - suffix
                end = total - 1
            } else {
                start = startText.toLongOrNull() ?: return null
                end = if (endText.isEmpty()) total - 1 else (endText.toLongOrNull() ?: return null)
                if (start < 0) start = 0
                if (end >= total) end = total - 1
            }
            if (start > end) return null
            Pair(start, end)
        } catch (e: Exception) {
            null
        }
    }

    private fun notFound(): WebResourceResponse {
        val bytes = "Not found".toByteArray(Charsets.UTF_8)
        val response = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(bytes))
        response.setStatusCodeAndReasonPhrase(404, "Not Found")
        response.responseHeaders = mapOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Cache-Control" to "no-store"
        )
        return response
    }

    private fun forbidden(): WebResourceResponse {
        val bytes = "Forbidden".toByteArray(Charsets.UTF_8)
        val response = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(bytes))
        response.setStatusCodeAndReasonPhrase(403, "Forbidden")
        response.responseHeaders = mapOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Cache-Control" to "no-store"
        )
        return response
    }

    private fun mimeType(name: String): Pair<String, String?> {
        val n = name.lowercase()
        return when {
            n.endsWith(".html") || n.endsWith(".htm") -> Pair("text/html", "utf-8")
            n.endsWith(".js") || n.endsWith(".mjs") -> Pair("text/javascript", "utf-8")
            n.endsWith(".css") -> Pair("text/css", "utf-8")
            n.endsWith(".json") || n.endsWith(".map") -> Pair("application/json", "utf-8")
            n.endsWith(".txt") || n.endsWith(".md") -> Pair("text/plain", "utf-8")
            n.endsWith(".xml") -> Pair("application/xml", "utf-8")
            n.endsWith(".svg") -> Pair("image/svg+xml", "utf-8")
            n.endsWith(".png") -> Pair("image/png", null)
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> Pair("image/jpeg", null)
            n.endsWith(".webp") -> Pair("image/webp", null)
            n.endsWith(".gif") -> Pair("image/gif", null)
            n.endsWith(".ico") -> Pair("image/x-icon", null)
            n.endsWith(".woff2") -> Pair("font/woff2", null)
            n.endsWith(".woff") -> Pair("font/woff", null)
            n.endsWith(".ttf") -> Pair("font/ttf", null)
            n.endsWith(".otf") -> Pair("font/otf", null)
            n.endsWith(".mp3") -> Pair("audio/mpeg", null)
            n.endsWith(".m4a") -> Pair("audio/mp4", null)
            n.endsWith(".wav") -> Pair("audio/wav", null)
            n.endsWith(".ogg") -> Pair("audio/ogg", null)
            n.endsWith(".webm") -> Pair("audio/webm", null)
            n.endsWith(".mp4") -> Pair("video/mp4", null)
            n.endsWith(".pdf") -> Pair("application/pdf", null)
            n.endsWith(".wasm") -> Pair("application/wasm", null)
            else -> Pair("application/octet-stream", null)
        }
    }

    private class LimitedInputStream(private val source: FileInputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val max = if (len.toLong() > remaining) remaining.toInt() else len
            val read = source.read(b, off, max)
            if (read > 0) remaining -= read
            return read
        }

        override fun close() {
            try { source.close() } catch (e: Exception) { }
        }
    }
}
