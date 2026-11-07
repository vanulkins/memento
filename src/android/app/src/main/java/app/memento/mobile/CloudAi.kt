package app.memento.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticated client for the Memento gateway. The upstream provider key never enters the APK. */
object CloudAi {
    private val base = BuildConfig.MEMENTO_AI_BASE_URL.trimEnd('/')
    private val bootstrap = BuildConfig.MEMENTO_APP_BOOTSTRAP_SECRET

    fun ask(task: String, system: String, user: String, history: JSONArray = JSONArray()): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val role = item.optString("role")
            val content = item.optString("content")
            if (role in setOf("user", "assistant") && content.isNotBlank()) {
                messages.put(JSONObject().put("role", role).put("content", content.take(30_000)))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", user.take(900_000)))
        val body = JSONObject().put("task", task).put("messages", messages).toString()
        return signedRequest(body, retry = true).optString("content").trim()
    }

    private fun installId(): String {
        val saved = MementoApp.prefs.get("cloud_install_id")
        if (!saved.isNullOrBlank()) return saved
        return UUID.randomUUID().toString().also { MementoApp.prefs.setSync("cloud_install_id", it) }
    }

    @Synchronized private fun secret(): String {
        MementoApp.prefs.get("cloud_install_secret")?.takeIf { it.isNotBlank() }?.let { return it }
        val id = installId()
        require(bootstrap.isNotBlank()) { "Облачные функции не настроены в этой сборке" }
        val request = JSONObject().put("install_id", id).put("proof", hmac(bootstrap, id)).toString()
        val response = request("$base/register", request, emptyMap())
        return response.optString("install_secret").also {
            require(it.isNotBlank()) { "Сервер не выдал ключ установки" }
            MementoApp.prefs.setSync("cloud_install_secret", it)
        }
    }

    private fun signedRequest(body: String, retry: Boolean): JSONObject {
        val id = installId()
        val timestamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val signature = hmac(secret(), "$timestamp\n$nonce\n${sha256(body)}")
        return try {
            request("$base/ai", body, mapOf(
                "X-Memento-Install" to id,
                "X-Memento-Timestamp" to timestamp,
                "X-Memento-Nonce" to nonce,
                "X-Memento-Signature" to signature
            ))
        } catch (e: Unauthorized) {
            if (!retry) throw e
            MementoApp.prefs.delete("cloud_install_secret")
            signedRequest(body, retry = false)
        }
    }

    private fun request(endpoint: String, body: String, headers: Map<String, String>): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401) throw Unauthorized()
            if (code !in 200..299) throw IllegalStateException("Сервис ИИ временно недоступен (HTTP $code)")
            return JSONObject(text)
        } finally { connection.disconnect() }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun hmac(key: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private class Unauthorized : IllegalStateException()
}
