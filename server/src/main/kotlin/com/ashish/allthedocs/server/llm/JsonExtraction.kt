package com.ashish.allthedocs.server.llm

import com.ashish.allthedocs.server.LlmOutputException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object JsonExtraction {
    private val citationToken = Regex("【[^】]*】")

    fun stripCitationTokens(content: String): String = citationToken.replace(content, "")

    fun extractJsonObject(content: String): JsonObject {
        val cleaned = stripCitationTokens(content)
        val candidates = topLevelObjects(cleaned)
        for (candidate in candidates.sortedByDescending { it.length }) {
            val parsed = runCatching { Json.parseToJsonElement(candidate) }.getOrNull()
            if (parsed is JsonObject) return parsed
        }
        throw LlmOutputException("LLM response did not contain a JSON object")
    }

    private fun topLevelObjects(text: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for ((index, char) in text.withIndex()) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> if (depth > 0) inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        result += text.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return result
    }
}
