package com.ashish.allthedocs.data

import com.ashish.allthedocs.api.AnalyzeScenarioResponse
import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.api.CuratedScenarioSummary
import com.ashish.allthedocs.api.DocumentRequirement
import com.ashish.allthedocs.api.FollowUpAnswer
import com.ashish.allthedocs.api.RequirementOrigin
import com.ashish.allthedocs.api.ResolveRequirementsRequest
import com.ashish.allthedocs.data.db.ScenarioDao
import com.ashish.allthedocs.data.db.ScenarioDraftDao
import com.ashish.allthedocs.data.db.ScenarioDraftEntity
import com.ashish.allthedocs.data.db.ScenarioEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class Scenario(
    val id: String,
    val title: String,
    val description: String,
    val countryCode: String?,
    val origin: RequirementOrigin,
    val requirements: List<DocumentRequirement>,
    val assumptions: List<String>,
    val answers: List<FollowUpAnswer>,
    val createdAtEpochMs: Long,
)

data class ScenarioDraft(
    val id: String,
    val description: String,
    val analysis: AnalyzeScenarioResponse?,
    val answers: Map<String, String>,
) {
    val isEmpty: Boolean get() = description.isBlank() && analysis == null
    val answeredCount: Int get() = analysis?.questions?.count { answers[it.id]?.isNotBlank() == true } ?: 0
}

class ScenarioRepository(
    private val dao: ScenarioDao,
    private val draftDao: ScenarioDraftDao,
    private val api: ApiClient,
) {
    val scenarios: Flow<List<Scenario>> = dao.observeAll().map { list -> list.map { it.toScenario() } }

    fun scenario(id: String): Flow<Scenario?> = dao.observe(id).map { it?.toScenario() }

    val drafts: Flow<List<ScenarioDraft>> = draftDao.observeAll().map { list -> list.map { it.toDraft() } }

    suspend fun draft(id: String): ScenarioDraft? = draftDao.get(id)?.toDraft()

    @OptIn(ExperimentalTime::class)
    suspend fun saveDraft(draft: ScenarioDraft) {
        if (draft.isEmpty) return deleteDraft(draft.id)
        draftDao.upsert(
            ScenarioDraftEntity(
                id = draft.id,
                description = draft.description,
                analysisJson = draft.analysis?.let { ApiJson.encodeToString(AnalyzeScenarioResponse.serializer(), it) },
                answersJson = ApiJson.encodeToString(answerMapSerializer, draft.answers),
                updatedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    suspend fun deleteDraft(id: String) = draftDao.delete(id)

    private val curatedMutex = Mutex()
    private var curatedCache: List<CuratedScenarioSummary>? = null

    suspend fun curatedScenarios(): List<CuratedScenarioSummary> = curatedMutex.withLock {
        curatedCache ?: api.curatedScenarios().scenarios.also { curatedCache = it }
    }

    suspend fun curatedScenario(id: String): CuratedScenarioSummary? = curatedScenarios().firstOrNull { it.id == id }

    suspend fun analyze(description: String): AnalyzeScenarioResponse = api.analyze(description)

    @OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
    suspend fun createScenario(
        description: String,
        analysis: AnalyzeScenarioResponse,
        answers: List<FollowUpAnswer>,
    ): String {
        val response = api.resolveRequirements(
            ResolveRequirementsRequest(
                description = description,
                title = analysis.title,
                countryCode = analysis.countryCode,
                curatedScenarioId = analysis.curatedScenarioId,
                answers = answers,
            ),
        )
        val id = Uuid.random().toString()
        dao.upsert(
            ScenarioEntity(
                id = id,
                title = response.title.ifBlank { analysis.title },
                description = description,
                countryCode = response.countryCode ?: analysis.countryCode,
                origin = response.origin.name,
                requirementsJson = ApiJson.encodeToString(requirementsSerializer, response.requirements),
                assumptionsJson = ApiJson.encodeToString(stringsSerializer, response.assumptions),
                answersJson = ApiJson.encodeToString(answersSerializer, answers),
                createdAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        return id
    }

    suspend fun delete(id: String) = dao.delete(id)

    private fun ScenarioDraftEntity.toDraft() = ScenarioDraft(
        id = id,
        description = description,
        analysis = analysisJson?.let { json ->
            runCatching { ApiJson.decodeFromString(AnalyzeScenarioResponse.serializer(), json) }.getOrNull()
        },
        answers = runCatching { ApiJson.decodeFromString(answerMapSerializer, answersJson) }.getOrDefault(emptyMap()),
    )

    private fun ScenarioEntity.toScenario() = Scenario(
        id = id,
        title = title,
        description = description,
        countryCode = countryCode,
        origin = RequirementOrigin.entries.firstOrNull { it.name == origin } ?: RequirementOrigin.LLM_KNOWLEDGE,
        requirements = decodeOrEmpty(requirementsSerializer, requirementsJson),
        assumptions = decodeOrEmpty(stringsSerializer, assumptionsJson),
        answers = decodeOrEmpty(answersSerializer, answersJson),
        createdAtEpochMs = createdAtEpochMs,
    )

    private companion object {
        val requirementsSerializer = ListSerializer(DocumentRequirement.serializer())
        val answersSerializer = ListSerializer(FollowUpAnswer.serializer())
        val stringsSerializer = ListSerializer(String.serializer())
        val answerMapSerializer = MapSerializer(String.serializer(), String.serializer())
    }
}

internal fun <T> decodeOrEmpty(serializer: kotlinx.serialization.KSerializer<List<T>>, json: String): List<T> =
    runCatching { ApiJson.decodeFromString(serializer, json) }.getOrDefault(emptyList())
