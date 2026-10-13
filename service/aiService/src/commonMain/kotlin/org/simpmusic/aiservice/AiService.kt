package org.simpmusic.aiservice

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatResponseFormat
import com.aallam.openai.api.chat.JsonSchema
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.OpenAIHost.Companion.Gemini
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.taste.TasteException
import com.maxrave.domain.data.model.taste.TasteInput
import com.maxrave.domain.data.model.taste.TasteReading
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.minutes

class AiService(
    private val aiHost: AIHost = AIHost.GEMINI,
    private val apiKey: String,
    private val customModelId: String? = null,
    private val customBaseUrl: String? = null,
    private val customHeaders: Map<String, String>? = null,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    private val openAI: OpenAI by lazy {
        when (aiHost) {
            AIHost.GEMINI -> {
                OpenAI(host = Gemini, token = apiKey, timeout = REQUEST_TIMEOUT)
            }

            AIHost.OPENAI -> {
                OpenAI(token = apiKey, timeout = REQUEST_TIMEOUT)
            }

            AIHost.CUSTOM_OPENAI -> {
                val baseUrl = customBaseUrl ?: "https://api.openai.com/v1/"
                val config =
                    OpenAIConfig(
                        token = apiKey,
                        timeout = REQUEST_TIMEOUT,
                        host = OpenAIHost(baseUrl = baseUrl),
                        headers = customHeaders ?: emptyMap(),
                    )
                OpenAI(config)
            }
        }
    }

    private val model by lazy {
        if (!customModelId.isNullOrEmpty()) {
            ModelId(customModelId)
        } else {
            when (aiHost) {
                // Fork: gemini-2.0-flash is retired (gone from the models list by 2026-10), and a request
                // for it failed with nothing shown to the user. 2.5-flash is the stable one that is left.
                AIHost.GEMINI -> ModelId("gemini-2.5-flash")
                AIHost.OPENAI -> ModelId("gpt-4o")
                AIHost.CUSTOM_OPENAI -> ModelId("gpt-4o")
            }
        }
    }

    /**
     * One reading of the listener's taste from [input].
     *
     * Sent without a `responseFormat`, unlike [translateLyrics]: providers reached through the
     * custom OpenAI-compatible path do not all accept `json_schema` (issue #2297 traced DeepSeek's
     * chat endpoint to `json_object` only), and a refused format fails the whole request. The
     * prompt asks for a bare JSON object instead, and [parseTasteReading] reads it leniently.
     */
    suspend fun describeTaste(input: TasteInput): TasteReading {
        val request =
            chatCompletionRequest {
                this.model = this@AiService.model
                messages {
                    system {
                        content = TASTE_SYSTEM_PROMPT
                    }
                    user {
                        content {
                            text(input.toPromptData())
                        }
                    }
                }
            }
        val raw =
            openAI
                .chatCompletion(request)
                .choices
                .firstOrNull()
                ?.message
                ?.content
                ?: throw TasteException(TasteException.Kind.TEMPORARY, message = "Empty response from AI")
        return parseTasteReading(raw)
            ?: throw TasteException(TasteException.Kind.TEMPORARY, message = "Unreadable response from AI")
    }

    suspend fun translateLyrics(
        inputLyrics: Lyrics,
        targetLanguage: String,
    ): Lyrics {
        val lines = inputLyrics.lines ?: throw IllegalStateException("No lyrics lines to translate")

        // Build key-value map: index -> words (only non-empty lines)
        val indexToWords = mutableMapOf<String, String>()
        lines.forEachIndexed { index, line ->
            val words = line.words.trim()
            if (words.isNotEmpty() && words != "♫") {
                indexToWords[index.toString()] = words
            }
        }

        if (indexToWords.isEmpty()) {
            throw IllegalStateException("No translatable lyrics lines found")
        }

        val inputJson = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), indexToWords)

        val request =
            chatCompletionRequest {
                this.model = this@AiService.model
                responseFormat = ChatResponseFormat.jsonSchema(aiResponseJsonSchema)
                messages {
                    system {
                        content =
                            // Fork: asks for lyrics a native speaker would write, not a word-for-word gloss.
                            "You translate song lyrics into natural, singable lyrics in the target language.\n" +
                            "\n" +
                            "INPUT:\n" +
                            "- A JSON object where keys are line indices and values are the lines of ONE song, in order.\n" +
                            "\n" +
                            "RULES:\n" +
                            "- FIRST read every line to understand the story, who is singing to whom, and the mood.\n" +
                            "- Detect the dominant language. If it is the SAME as the target language, return an EMPTY \"translations\" object.\n" +
                            "- Translate the meaning and feeling, not word for word. Each line should read like a line a native songwriter would write: natural word order, everyday vocabulary, no stiff or literal phrasing, no explanations.\n" +
                            "- Keep one consistent voice and pronouns for the whole song. For Vietnamese, pick the pair that fits the relationship (usually anh/em for a love song) and never switch mid-song.\n" +
                            "- Keep lines about as short as the original; do not pad or add meaning that is not there.\n" +
                            "- Translate repeated lines identically. Keep names as they are. Keep pure vocalizations (oh, yeah, la la) unchanged.\n" +
                            "- Keep ALL keys exactly the same and output EXACTLY the same number of entries; never merge, split, add or remove entries.\n" +
                            "\n" +
                            "OUTPUT:\n" +
                            "- A JSON object with the \"translations\" field mapping the same keys to translated lines (or an empty object when the input is already in the target language)."
                    }
                    user {
                        content {
                            text("Target language: $targetLanguage")
                        }
                        content {
                            text("Input lyrics: $inputJson")
                        }
                    }
                }
            }
        val completion: ChatCompletion = openAI.chatCompletion(request)
        val jsonContent =
            completion.choices
                .firstOrNull()
                ?.message
                ?.content ?: throw IllegalStateException("No response from AI")
        val jsonData =
            Regex(
                "```json\\s*([\\s\\S]*?)```",
            ).find(jsonContent)
                ?.groups
                ?.firstOrNull()
                ?.value ?: jsonContent
        val cleanedJson = jsonData.replace("```json", "").replace("```", "")
        val translationResponse = json.decodeFromString<TranslationResponse>(cleanedJson)
        val translatedMap = translationResponse.translations
        if (translatedMap.isEmpty()) {
            throw IllegalStateException(
                "Input lyrics are already in the target language ($targetLanguage). Translation aborted.",
            )
        }

        // Map translated text back to original lines, preserving all timestamps
        val translatedLines = lines.mapIndexed { index, originalLine ->
            val translatedWords = translatedMap[index.toString()]
            if (translatedWords != null) {
                Line(
                    startTimeMs = originalLine.startTimeMs,
                    endTimeMs = originalLine.endTimeMs,
                    words = translatedWords,
                    syllables = null,
                )
            } else {
                // Non-translatable line (empty or ♫): keep original
                Line(
                    startTimeMs = originalLine.startTimeMs,
                    endTimeMs = originalLine.endTimeMs,
                    words = originalLine.words,
                    syllables = originalLine.syllables,
                )
            }
        }

        return Lyrics(
            error = false,
            lines = translatedLines,
            syncType = inputLyrics.syncType,
        )
    }

    companion object {
        /**
         * How long a request may go without a byte coming back. The library's default is 30 s,
         * which a model that thinks before answering — or a router in front of one — exceeds on an
         * open-ended prompt like the taste reading, and the request then dies with
         * "Socket timeout has expired" while the provider is still working on it.
         */
        private val REQUEST_TIMEOUT = Timeout(socket = 2.minutes)

        private val translationJsonSchema: JsonObject =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("translations") {
                        put("type", "object")
                        putJsonObject("additionalProperties") {
                            put("type", "string")
                        }
                    }
                }
                putJsonArray("required") {
                    add("translations")
                }
            }
        private val aiResponseJsonSchema =
            JsonSchema(
                name = "ai_translation_schema",
                schema = translationJsonSchema,
                strict = false,
            )
    }
}

@kotlinx.serialization.Serializable
data class TranslationResponse(
    val translations: Map<String, String> = emptyMap(),
)

enum class AIHost {
    GEMINI,
    OPENAI,
    CUSTOM_OPENAI,
}