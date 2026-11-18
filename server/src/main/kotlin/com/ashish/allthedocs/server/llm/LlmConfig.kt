package com.ashish.allthedocs.server.llm

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val textModel: String,
    val searchModel: String,
    val visionModel: String,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
        const val DEFAULT_TEXT_MODEL = "openai/gpt-oss-120b"
        const val DEFAULT_SEARCH_MODEL = "openai/gpt-oss-120b"
        const val DEFAULT_VISION_MODEL = "qwen/qwen3.8-27b"

        fun fromEnvironment(env: (String) -> String?): LlmConfig? {
            fun value(name: String): String? = env(name)?.trim()?.takeIf { it.isNotEmpty() }
            val apiKey = value("LLM_API_KEY") ?: value("GROQ_API_KEY") ?: return null
            return LlmConfig(
                baseUrl = (value("LLM_BASE_URL") ?: DEFAULT_BASE_URL).trimEnd('/'),
                apiKey = apiKey,
                textModel = value("LLM_TEXT_MODEL") ?: DEFAULT_TEXT_MODEL,
                searchModel = value("LLM_SEARCH_MODEL") ?: DEFAULT_SEARCH_MODEL,
                visionModel = value("LLM_VISION_MODEL") ?: DEFAULT_VISION_MODEL,
            )
        }
    }
}
