package app.memento.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Summary providers exposed by the original desktop UI, adapted to Android HTTP/local execution. */
class SummaryEngine {
    fun process(args: JSONObject, modelConfig: JSONObject, customOpenAi: JSONObject): JSONObject {
        val meetingId = args.optString("meetingId")
        val transcript = args.optString("text").trim()
        require(meetingId.isNotBlank()) { "Не выбрана встреча" }
        require(transcript.isNotBlank()) { "Нет текста расшифровки" }
        val provider = args.optString("model", modelConfig.optString("provider", "builtin-ai"))
        val model = args.optString("modelName", modelConfig.optString("model", "memento-local"))
        val customPrompt = args.optString("customPrompt").trim()
        val templateId = args.optString("templateId", "standard_meeting")
        val language = args.optString("summaryLanguage", "auto")
        val prompt = prompt(transcript, templateId, customPrompt, language)
        var cloudMetadata: JSONObject? = null
        val markdown = when (provider) {
            "builtin-ai" -> {
                val raw = CloudAi.ask("summary", originalSystemPrompt(templateId, customPrompt, language), transcript)
                cloudMetadata = parseCloudSummary(raw)
                cloudMetadata?.optString("markdown")?.takeIf { it.isNotBlank() } ?: raw
            }
            "ollama" -> ollama(modelConfig.optString("ollamaEndpoint", "http://127.0.0.1:11434"), model, prompt)
            "claude", "anthropic" -> anthropic(model, apiKey(provider, modelConfig), prompt)
            "custom-openai" -> openAiCompatible(
                normalizeChatEndpoint(customOpenAi.optString("endpoint")),
                customOpenAi.optString("apiKey"),
                customOpenAi.optString("model", model),
                prompt,
                emptyMap()
            )
            "openai" -> openAiCompatible("https://api.openai.com/v1/chat/completions", apiKey(provider, modelConfig), model, prompt, emptyMap())
            "groq" -> openAiCompatible("https://api.groq.com/openai/v1/chat/completions", apiKey(provider, modelConfig), model, prompt, emptyMap())
            "openrouter" -> openAiCompatible(
                "https://openrouter.ai/api/v1/chat/completions",
                apiKey(provider, modelConfig),
                model,
                prompt,
                mapOf("HTTP-Referer" to "https://memento.app", "X-Title" to "Memento Mobile")
            )
            "deepseek" -> openAiCompatible("https://api.deepseek.com/chat/completions", apiKey(provider, modelConfig), model, prompt, emptyMap())
            "gigachat" -> openAiCompatible("https://gigachat.devices.sberbank.ru/api/v1/chat/completions", apiKey(provider, modelConfig), model, prompt, emptyMap())
            else -> throw IllegalArgumentException("Провайдер суммаризации $provider не поддерживается")
        }.trim()
        require(markdown.isNotBlank()) { "Модель вернула пустое саммари" }
        val title = cloudMetadata?.optString("short_title")?.takeIf { it.isNotBlank() }?.take(80)
            ?: markdown.lineSequence().firstOrNull { it.trim().startsWith("#") }
            ?.trim()?.trimStart('#')?.trim()?.take(80) ?: "Итоги встречи"
        val summary = JSONObject()
            .put("markdown", markdown)
            .put("short_title", title)
            .put("short_description", compactDescription(cloudMetadata?.optString("short_description")
                ?.takeIf { it.isNotBlank() } ?: firstSentence(markdown)))
            .put("participants", MementoApp.db.participantNames(meetingId))
            .put("provider", provider)
            .put("model", model)
        MementoApp.db.saveSummary(meetingId, summary)
        MementoApp.db.applyGeneratedTitle(meetingId, title, args.optBoolean("replaceGeneratedTitle", false))
        return JSONObject().put("process_id", "android-" + System.currentTimeMillis()).put("summary", summary)
    }

    fun listBuiltInModels(): JSONArray = JSONArray().put(JSONObject()
        .put("name", "deepseek-v4-pro")
        .put("display_name", "DeepSeek V4 Pro")
        .put("description", "Модель для итогов Memento")
        .put("size_mb", 0)
        .put("status", JSONObject().put("type", "available")))

    fun ollamaModels(endpoint: String): JSONArray {
        val base = endpoint.trimEnd('/').ifBlank { "http://127.0.0.1:11434" }
        val response = request("GET", "$base/api/tags", null, emptyMap())
        return response.optJSONArray("models") ?: JSONArray()
    }

    fun testCustom(config: JSONObject): JSONObject {
        val result = openAiCompatible(
            normalizeChatEndpoint(config.optString("endpoint")),
            config.optString("apiKey"),
            config.optString("model"),
            "Ответь одним словом: OK",
            emptyMap()
        )
        return JSONObject().put("success", result.isNotBlank()).put("message", result.take(120))
    }

    private fun apiKey(provider: String, config: JSONObject): String =
        config.optString("apiKey").takeIf { it.isNotBlank() }
            ?: MementoApp.prefs.get("api_key_$provider").orEmpty()

    private fun prompt(transcript: String, template: String, custom: String, language: String): String {
        val focus = when (template) {
            "daily_standup" -> "Сохрани структуру стендапа: прогресс, планы, блокеры, владельцы и сроки."
            "one_on_one" -> "Сохрани структуру 1-на-1: состояние, темы, обратная связь, договорённости и следующие шаги."
            "interview_memory" -> "Сохрани структуру интервью: вводные, опыт, наблюдения, риски и следующие шаги."
            "project_sync" -> "Сохрани структуру синхронизации проекта: статус, решения, риски, блокеры, владельцы и сроки."
            else -> "Выдели контекст, решения, договорённости, риски и следующие шаги с владельцами и сроками."
        }
        val lang = if (language == "auto" || language.isBlank()) "на языке расшифровки" else "на языке $language"
        return """
            Ты — ассистент Memento. Составь точное саммари встречи $lang в Markdown.
            Не придумывай факты и не скрывай важные разногласия. $focus
            ${if (custom.isBlank()) "" else "Дополнительная инструкция пользователя: $custom"}

            РАСШИФРОВКА:
            $transcript
        """.trimIndent()
    }

    private fun originalSystemPrompt(template: String, custom: String, language: String): String {
        val focus = when (template) {
            "daily_standup" -> "Сохрани прогресс каждого участника, планы, блокеры, ответственных и сроки."
            "one_on_one" -> "Сохрани состояние участников, обратную связь, договорённости и следующие шаги."
            "interview_memory" -> "Сохрани вводные, опыт, наблюдения, риски и следующие шаги интервью."
            "retrospective" -> "Сохрани результаты, что сработало, что мешало, решения и действия по улучшению."
            else -> "Сохрани существенные цели, статусы, решения, числа, сроки, блокеры и открытые вопросы."
        }
        val lang = if (language == "auto" || language.isBlank()) "язык стенограммы" else language
        return """
            Ты — ассистент Memento. Составь рабочее саммари встречи по стенограмме. Человек, который не присутствовал, должен понять ситуацию и дальнейшие действия.
            Начни с общих вводных, целей и требований; затем раскрой отдельные темы. Используй заголовки и маркированные списки, без таблиц и нумерованных списков. Под заголовками пиши только буллеты.
            $focus Убирай повторы и речевой шум, но не важные детали. Не добавляй сведения от себя, не превращай предложения в договорённости. Если что-то неясно или противоречиво, сохрани эту неопределённость.
            В «Следующих шагах» каждый шаг оформи отдельным буллетом: ответственный и действие, вложенным буллетом срок или условие. Если ответственный не определён, укажи это прямо.
            Пиши коротко и естественно на языке: $lang. ${if (custom.isBlank()) "" else "Дополнительная инструкция: $custom"}
            Верни ТОЛЬКО JSON без markdown-ограждения: {"markdown":"полное саммари Markdown","short_title":"короткое название встречи","short_description":"одно естественное описание строго от 3 до 8 слов"}.
        """.trimIndent()
    }

    private fun parseCloudSummary(raw: String): JSONObject? {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { JSONObject(clean) }.getOrNull()?.let { return it }
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull()
    }

    private fun compactDescription(value: String): String {
        val clean = value.replace(Regex("\\s+"), " ").trim().take(160)
        val words = clean.split(' ').filter { it.isNotBlank() }
        if (words.size <= 8) return clean
        return words.take(8).joinToString(" ").trimEnd('.', ',', ';', ':', '—', '–', '-') + "…"
    }

    private fun localSummary(transcript: String, template: String): String {
        val lines = transcript.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val sentences = lines.flatMap { it.split(Regex("(?<=[.!?])\\s+")) }.map { it.trim() }.filter { it.length > 12 }
        val decisions = sentences.filter { Regex("реш|договор|фиксир|итог|соглас", RegexOption.IGNORE_CASE).containsMatchIn(it) }.take(8)
        val actions = sentences.filter { Regex("сдела|пришл|подготов|провер|срок|до ", RegexOption.IGNORE_CASE).containsMatchIn(it) }.take(8)
        val context = sentences.take(6)
        fun bullets(values: List<String>) = if (values.isEmpty()) "- Не выделено автоматически" else values.joinToString("\n") { "- $it" }
        val title = when (template) {
            "daily_standup" -> "Итоги стендапа"
            "one_on_one" -> "Итоги встречи 1-на-1"
            "interview_memory" -> "Итоги интервью"
            else -> "Итоги встречи"
        }
        return "# $title\n\n## Контекст\n${bullets(context)}\n\n## Решения и договорённости\n${bullets(decisions)}\n\n## Следующие шаги\n${bullets(actions)}"
    }

    private fun ollama(endpoint: String, model: String, prompt: String): String {
        require(model.isNotBlank()) { "Не выбрана модель Ollama" }
        val body = JSONObject().put("model", model).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val response = request("POST", endpoint.trimEnd('/') + "/api/chat", body, emptyMap())
        return response.optJSONObject("message")?.optString("content").orEmpty()
    }

    private fun anthropic(model: String, key: String, prompt: String): String {
        require(key.isNotBlank()) { "Не указан API-ключ Anthropic" }
        val body = JSONObject().put("model", model).put("max_tokens", 4096)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val response = request(
            "POST",
            "https://api.anthropic.com/v1/messages",
            body,
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        )
        val content = response.optJSONArray("content") ?: JSONArray()
        return (0 until content.length()).joinToString("\n") { content.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun openAiCompatible(endpoint: String, key: String, model: String, prompt: String, headers: Map<String, String>): String {
        require(endpoint.isNotBlank()) { "Не указан адрес API" }
        require(model.isNotBlank()) { "Не выбрана модель суммаризации" }
        require(key.isNotBlank() || endpoint.contains("127.0.0.1") || endpoint.contains("localhost")) { "Не указан API-ключ" }
        val body = JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("temperature", 0.2)
        val allHeaders = LinkedHashMap(headers)
        if (key.isNotBlank()) allHeaders["Authorization"] = "Bearer $key"
        val response = request("POST", endpoint, body, allHeaders)
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
        return choice?.optJSONObject("message")?.optString("content")
            ?: choice?.optString("text").orEmpty()
    }

    private fun request(method: String, endpoint: String, body: JSONObject?, headers: Map<String, String>): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "HTTP $code: ${text.take(300)}")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeChatEndpoint(endpoint: String): String {
        val clean = endpoint.trim().trimEnd('/')
        require(clean.startsWith("http://") || clean.startsWith("https://")) { "Некорректный адрес API" }
        return when {
            clean.endsWith("/chat/completions") -> clean
            clean.endsWith("/v1") -> "$clean/chat/completions"
            else -> "$clean/v1/chat/completions"
        }
    }

    private fun firstSentence(markdown: String): String = markdown.lineSequence()
        .map { it.trim().trimStart('#', '-', '*').trim() }
        .firstOrNull { it.isNotBlank() }
        ?.take(160) ?: "Саммари встречи"
}
