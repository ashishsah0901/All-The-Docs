package com.ashish.allthedocs.server.llm

class LlmRequest(
    val systemPrompt: String,
    val userText: String,
    val images: List<ByteArray> = emptyList(),
    val jsonMode: Boolean = true,
    val webSearch: Boolean = false,
)

interface LlmProvider {
    suspend fun complete(request: LlmRequest): String
}
