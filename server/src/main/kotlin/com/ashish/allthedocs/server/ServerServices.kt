package com.ashish.allthedocs.server

import com.ashish.allthedocs.server.llm.LlmConfig
import com.ashish.allthedocs.server.llm.LlmProvider
import com.ashish.allthedocs.server.llm.OpenAiCompatibleLlmProvider
import com.ashish.allthedocs.server.rules.CuratedScenarioRepository
import com.ashish.allthedocs.server.scenario.ScenarioService
import com.ashish.allthedocs.server.verify.DocumentVerifier
import com.ashish.allthedocs.server.verify.SignatureChecker
import com.ashish.allthedocs.server.verify.TrustAnchors
import io.ktor.client.HttpClient
import kotlinx.datetime.LocalDate
import java.time.ZoneOffset

class ServerServices(
    val llm: LlmProvider?,
    curated: CuratedScenarioRepository = CuratedScenarioRepository.default,
    today: () -> LocalDate = ::utcToday,
    val signatures: SignatureChecker = SignatureChecker(TrustAnchors.bundled()),
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    val scenarios = ScenarioService(llm, curated)
    val verifier = DocumentVerifier(llm, today)

    override fun close() = onClose()

    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): ServerServices {
            val signatures = SignatureChecker(TrustAnchors.fromEnvironment(env))
            val config = LlmConfig.fromEnvironment(env) ?: return ServerServices(llm = null, signatures = signatures)
            val httpClient: HttpClient = OpenAiCompatibleLlmProvider.defaultHttpClient()
            return ServerServices(
                llm = OpenAiCompatibleLlmProvider(config, httpClient),
                signatures = signatures,
                onClose = { httpClient.close() },
            )
        }

        fun utcToday(): LocalDate {
            val now = java.time.LocalDate.now(ZoneOffset.UTC)
            return LocalDate(now.year, now.monthValue, now.dayOfMonth)
        }
    }
}
