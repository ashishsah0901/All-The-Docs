package com.ashish.allthedocs.server.llm

import com.ashish.allthedocs.server.LlmOutputException
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonExtractionTest {
    @Test
    fun extractsObjectFromFencedBlock() {
        val content = "Here you go:\n```json\n{\"title\": \"Visa\"}\n```"
        assertEquals("Visa", JsonExtraction.extractJsonObject(content)["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun stripsCitationTokensAndPrefersLargestObject() {
        val content = """Snippet {"a":1}【3†L1-L4】 final: {"title":"Passport 【1†source】","requirements":[{"name":"x"}]}"""
        val json = JsonExtraction.extractJsonObject(content)
        assertEquals("Passport ", json["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun handlesBracesInsideStrings() {
        val json = JsonExtraction.extractJsonObject("""{"title":"a } b { c","n":"\"q\""}""")
        assertEquals("a } b { c", json["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun throwsWhenNoObjectPresent() {
        assertFailsWith<LlmOutputException> { JsonExtraction.extractJsonObject("no json here") }
        assertFailsWith<LlmOutputException> { JsonExtraction.extractJsonObject("{broken") }
    }
}
