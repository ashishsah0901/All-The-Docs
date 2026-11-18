package com.ashish.allthedocs.ui.scenario

import com.ashish.allthedocs.api.AnalyzeScenarioResponse
import com.ashish.allthedocs.api.ApiJson
import com.ashish.allthedocs.api.ApiRoutes
import com.ashish.allthedocs.api.CuratedScenarioSummary
import com.ashish.allthedocs.api.CuratedScenariosResponse
import com.ashish.allthedocs.api.DocumentRequirement
import com.ashish.allthedocs.api.FollowUpQuestion
import com.ashish.allthedocs.api.RequirementOrigin
import com.ashish.allthedocs.api.ResolveRequirementsRequest
import com.ashish.allthedocs.api.ResolveRequirementsResponse
import com.ashish.allthedocs.data.ApiClient
import com.ashish.allthedocs.data.ScenarioRepository
import com.ashish.allthedocs.data.db.ScenarioDao
import com.ashish.allthedocs.data.db.ScenarioDraftDao
import com.ashish.allthedocs.data.db.ScenarioDraftEntity
import com.ashish.allthedocs.data.db.ScenarioEntity
import com.ashish.allthedocs.model.DocumentType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NewScenarioViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeScenarioDao()
    private val draftDao = FakeScenarioDraftDao()
    private val resolveRequests = mutableListOf<ResolveRequirementsRequest>()
    private var analysis = AnalyzeScenarioResponse(
        title = "Home loan",
        countryCode = "IN",
        curatedScenarioId = "in.home_loan",
        questions = listOf(FollowUpQuestion("employment", "Employment?", listOf("Salaried", "Self-employed"))),
    )

    private val api = ApiClient(
        ApiClient.createHttpClient(
            MockEngine { request ->
                val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                when (request.url.encodedPath) {
                    ApiRoutes.CURATED_SCENARIOS -> respond(
                        ApiJson.encodeToString(
                            CuratedScenariosResponse.serializer(),
                            CuratedScenariosResponse(listOf(curatedHomeLoan, curatedSavings)),
                        ),
                        HttpStatusCode.OK,
                        headers,
                    )
                    ApiRoutes.ANALYZE_SCENARIO ->
                        respond(ApiJson.encodeToString(AnalyzeScenarioResponse.serializer(), analysis), HttpStatusCode.OK, headers)
                    else -> {
                        val body = (request.body as TextContent).text
                        resolveRequests += ApiJson.decodeFromString(ResolveRequirementsRequest.serializer(), body)
                        val response = ResolveRequirementsResponse(
                            title = "Home loan",
                            countryCode = "IN",
                            origin = RequirementOrigin.CURATED,
                            requirements = listOf(
                                DocumentRequirement("pan", "PAN card", acceptedTypes = listOf(DocumentType.PAN_CARD)),
                            ),
                        )
                        respond(ApiJson.encodeToString(ResolveRequirementsResponse.serializer(), response), HttpStatusCode.OK, headers)
                    }
                }
            },
        ),
    ) { "http://server.test" }

    private val curatedHomeLoan = CuratedScenarioSummary(
        id = "in.home_loan",
        title = "Home loan",
        summary = "Applying for a home loan in India.",
        countryCode = "IN",
        questions = listOf(FollowUpQuestion("employment", "Employment?", listOf("Salaried", "Self-employed"))),
    )
    private val curatedSavings = CuratedScenarioSummary(
        id = "in.savings_account",
        title = "Savings account",
        summary = "Opening a savings account in India.",
        countryCode = "IN",
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun analyzeShowsQuestionsThenSubmitCreatesScenario() = runTest(dispatcher) {
        val viewModel = newViewModel()

        viewModel.onDescriptionChange("Home loan for my new flat")
        viewModel.analyze()

        val afterAnalyze = viewModel.state.first { it.analysis != null && it.busyMessage == null }
        assertEquals(1, afterAnalyze.analysis?.questions?.size)
        assertFalse(afterAnalyze.canSubmit)
        assertNull(afterAnalyze.createdScenarioId)

        viewModel.onAnswer("employment", "Salaried")
        assertTrue(viewModel.state.value.canSubmit)
        viewModel.submit()

        val createdId = assertNotNull(viewModel.state.first { it.createdScenarioId != null }.createdScenarioId)
        val saved = assertNotNull(dao.items.value[createdId])
        assertEquals("Home loan", saved.title)
        assertEquals("CURATED", saved.origin)
        assertEquals("Salaried", resolveRequests.single().answers.single().answer)
        assertEquals("in.home_loan", resolveRequests.single().curatedScenarioId)
    }

    @Test
    fun scenarioWithoutQuestionsIsCreatedRightAway() = runTest(dispatcher) {
        analysis = analysis.copy(questions = emptyList())
        val viewModel = newViewModel()

        viewModel.onDescriptionChange("Open a savings account")
        viewModel.analyze()

        val created = viewModel.state.first { it.createdScenarioId != null && it.busyMessage == null }
        assertNotNull(created.createdScenarioId)
    }

    @Test
    fun progressIsSavedAsDraftAndRestoredByDraftId() = runTest(dispatcher) {
        val first = newViewModel()
        first.onDescriptionChange("Home loan for my new flat")
        advanceUntilIdle()
        assertEquals("Home loan for my new flat", draftDao.items.value[DRAFT_ID]?.description)

        first.analyze()
        first.state.first { it.analysis != null && it.busyMessage == null }
        first.onAnswer("employment", "Salaried")
        advanceUntilIdle()

        val restored = newViewModel().state.first { it.analysis != null }
        assertEquals("Home loan for my new flat", restored.description)
        assertEquals("in.home_loan", restored.analysis?.curatedScenarioId)
        assertEquals(mapOf("employment" to "Salaried"), restored.answers)
        assertTrue(restored.canSubmit)
    }

    @Test
    fun creatingTheScenarioRemovesTheDraft() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onDescriptionChange("Home loan for my new flat")
        viewModel.analyze()
        viewModel.state.first { it.analysis != null && it.busyMessage == null }
        viewModel.onAnswer("employment", "Salaried")
        advanceUntilIdle()
        assertNotNull(draftDao.items.value[DRAFT_ID])

        viewModel.submit()
        viewModel.state.first { it.createdScenarioId != null }
        advanceUntilIdle()

        assertNull(draftDao.items.value[DRAFT_ID])
        assertEquals(1, dao.items.value.size)
    }

    @Test
    fun clearingTheDescriptionRemovesTheDraft() = runTest(dispatcher) {
        val viewModel = newViewModel()
        viewModel.onDescriptionChange("Home loan")
        advanceUntilIdle()
        assertNotNull(draftDao.items.value[DRAFT_ID])

        viewModel.onDescriptionChange("")
        advanceUntilIdle()

        assertNull(draftDao.items.value[DRAFT_ID])
    }

    @Test
    fun curatedStartIsSavedAsDraftAndRestoredWithoutCuratedId() = runTest(dispatcher) {
        val started = newViewModel(curatedScenarioId = "in.home_loan")
        started.state.first { it.analysis != null && it.busyMessage == null }
        advanceUntilIdle()
        assertEquals("Applying for a home loan in India.", draftDao.items.value[DRAFT_ID]?.description)

        val restored = newViewModel().state.first { it.analysis != null }

        assertEquals("in.home_loan", restored.analysis?.curatedScenarioId)
        assertEquals(1, restored.analysis?.questions?.size)
    }

    private fun newViewModel(curatedScenarioId: String? = null) =
        NewScenarioViewModel(DRAFT_ID, curatedScenarioId, ScenarioRepository(dao, draftDao, api))

    private companion object {
        const val DRAFT_ID = "draft-1"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NewScenarioViewModelCuratedTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun api(curated: List<CuratedScenarioSummary>, onResolve: (ResolveRequirementsRequest) -> Unit) = ApiClient(
        ApiClient.createHttpClient(
            MockEngine { request ->
                val headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                if (request.url.encodedPath == ApiRoutes.CURATED_SCENARIOS) {
                    respond(
                        ApiJson.encodeToString(CuratedScenariosResponse.serializer(), CuratedScenariosResponse(curated)),
                        HttpStatusCode.OK,
                        headers,
                    )
                } else {
                    onResolve(ApiJson.decodeFromString(ResolveRequirementsRequest.serializer(), (request.body as TextContent).text))
                    val response = ResolveRequirementsResponse("Savings account", "IN", RequirementOrigin.CURATED, emptyList())
                    respond(ApiJson.encodeToString(ResolveRequirementsResponse.serializer(), response), HttpStatusCode.OK, headers)
                }
            },
        ),
    ) { "http://server.test" }

    @Test
    fun curatedStartPrefillsQuestionsWithoutAnalyzeCall() = runTest(dispatcher) {
        val curated = CuratedScenarioSummary(
            "in.home_loan",
            "Home loan",
            "Applying for a home loan in India.",
            "IN",
            listOf(FollowUpQuestion("employment", "Employment?", listOf("Salaried"))),
        )
        val viewModel = NewScenarioViewModel("draft-1", "in.home_loan", ScenarioRepository(FakeScenarioDao(), FakeScenarioDraftDao(), api(listOf(curated)) {}))

        val state = viewModel.state.first { it.analysis != null && it.busyMessage == null }

        assertEquals("in.home_loan", state.analysis?.curatedScenarioId)
        assertEquals("Applying for a home loan in India.", state.description)
        assertNull(state.createdScenarioId)
    }

    @Test
    fun curatedStartWithoutQuestionsCreatesScenarioRightAway() = runTest(dispatcher) {
        val resolved = mutableListOf<ResolveRequirementsRequest>()
        val curated = CuratedScenarioSummary("in.savings_account", "Savings account", "Opening an account.", "IN")
        val viewModel = NewScenarioViewModel("draft-1", "in.savings_account", ScenarioRepository(FakeScenarioDao(), FakeScenarioDraftDao(), api(listOf(curated)) { resolved += it }))

        val state = viewModel.state.first { it.createdScenarioId != null }

        assertNotNull(state.createdScenarioId)
        assertEquals("in.savings_account", resolved.single().curatedScenarioId)
    }

    @Test
    fun unknownCuratedIdShowsError() = runTest(dispatcher) {
        val viewModel = NewScenarioViewModel("draft-1", "in.missing", ScenarioRepository(FakeScenarioDao(), FakeScenarioDraftDao(), api(emptyList()) {}))

        val state = viewModel.state.first { it.error != null }

        assertEquals("This scenario is no longer available", state.error)
    }
}

private class FakeScenarioDao : ScenarioDao {
    val items = MutableStateFlow<Map<String, ScenarioEntity>>(emptyMap())

    override fun observeAll(): Flow<List<ScenarioEntity>> = items.map { it.values.sortedByDescending { e -> e.createdAtEpochMs } }

    override fun observe(id: String): Flow<ScenarioEntity?> = items.map { it[id] }

    override suspend fun upsert(entity: ScenarioEntity) {
        items.value = items.value + (entity.id to entity)
    }

    override suspend fun delete(id: String) {
        items.value = items.value - id
    }
}

private class FakeScenarioDraftDao : ScenarioDraftDao {
    val items = MutableStateFlow<Map<String, ScenarioDraftEntity>>(emptyMap())

    override fun observeAll(): Flow<List<ScenarioDraftEntity>> = items.map { it.values.sortedByDescending { e -> e.updatedAtEpochMs } }

    override suspend fun get(id: String): ScenarioDraftEntity? = items.value[id]

    override suspend fun upsert(entity: ScenarioDraftEntity) {
        items.value = items.value + (entity.id to entity)
    }

    override suspend fun delete(id: String) {
        items.value = items.value - id
    }
}
