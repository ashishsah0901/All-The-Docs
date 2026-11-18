package com.ashish.allthedocs.ui.scenario

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashish.allthedocs.api.AnalyzeScenarioResponse
import com.ashish.allthedocs.api.CuratedScenarioSummary
import com.ashish.allthedocs.api.FollowUpAnswer
import com.ashish.allthedocs.checklist.ChecklistEvaluator
import com.ashish.allthedocs.checklist.RequirementState
import com.ashish.allthedocs.checklist.RequirementStatus
import com.ashish.allthedocs.data.ApiException
import com.ashish.allthedocs.data.Scenario
import com.ashish.allthedocs.data.ScenarioDraft
import com.ashish.allthedocs.data.ScenarioRepository
import com.ashish.allthedocs.data.VaultRepository
import com.ashish.allthedocs.ui.today
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface QuickStartState {
    data object Loading : QuickStartState
    data class Loaded(val scenarios: List<CuratedScenarioSummary>) : QuickStartState
    data class Failed(val message: String) : QuickStartState
}

class ScenarioListViewModel(private val repository: ScenarioRepository) : ViewModel() {
    val scenarios: StateFlow<List<Scenario>?> = repository.scenarios
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val drafts: StateFlow<List<ScenarioDraft>> = repository.drafts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _quickStart = MutableStateFlow<QuickStartState>(QuickStartState.Loading)
    val quickStart: StateFlow<QuickStartState> = _quickStart.asStateFlow()

    init {
        loadQuickStart()
    }

    fun loadQuickStart() {
        _quickStart.value = QuickStartState.Loading
        viewModelScope.launch {
            _quickStart.value = try {
                QuickStartState.Loaded(repository.curatedScenarios())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                QuickStartState.Failed(e.message ?: "Couldn't load common scenarios")
            }
        }
    }

    fun discardDraft(id: String) {
        viewModelScope.launch { repository.deleteDraft(id) }
    }
}

data class NewScenarioUiState(
    val description: String = "",
    val analysis: AnalyzeScenarioResponse? = null,
    val answers: Map<String, String> = emptyMap(),
    val busyMessage: String? = null,
    val error: String? = null,
    val createdScenarioId: String? = null,
) {
    val canAnalyze: Boolean get() = description.isNotBlank() && busyMessage == null
    val canSubmit: Boolean
        get() = busyMessage == null && analysis != null &&
            analysis.questions.all { answers[it.id]?.isNotBlank() == true }
}

class NewScenarioViewModel(
    private val draftId: String,
    curatedScenarioId: String?,
    private val repository: ScenarioRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NewScenarioUiState())
    val state: StateFlow<NewScenarioUiState> = _state.asStateFlow()

    private val draftSync: Job = viewModelScope.launch {
        val persisted = repository.draft(draftId)
        when {
            persisted != null -> _state.update {
                it.copy(description = persisted.description, analysis = persisted.analysis, answers = persisted.answers)
            }
            curatedScenarioId != null -> startCurated(curatedScenarioId)
        }
        val unchanged = persisted ?: ScenarioDraft(draftId, "", null, emptyMap())
        _state.map { it.toDraft() }
            .distinctUntilChanged()
            .dropWhile { it == unchanged }
            .collect { repository.saveDraft(it) }
    }

    private fun startCurated(id: String) {
        runBusy("Loading scenario…") {
            val curated = repository.curatedScenario(id) ?: throw ApiException("This scenario is no longer available")
            _state.update { it.copy(description = curated.summary, analysis = curated.toAnalysis(), answers = emptyMap()) }
            if (curated.questions.isEmpty()) createScenario()
        }
    }

    fun onDescriptionChange(value: String) = _state.update { it.copy(description = value, error = null) }

    fun onAnswer(questionId: String, value: String) =
        _state.update { it.copy(answers = it.answers + (questionId to value), error = null) }

    fun editDescription() = _state.update { it.copy(analysis = null, answers = emptyMap(), error = null) }

    fun analyze() {
        val description = _state.value.description.trim()
        if (description.isEmpty()) return
        runBusy("Understanding your scenario…") {
            val analysis = repository.analyze(description)
            _state.update { it.copy(analysis = analysis, answers = emptyMap()) }
            if (analysis.questions.isEmpty()) createScenario()
        }
    }

    fun submit() {
        if (!_state.value.canSubmit) return
        runBusy("Working out the documents you need…") { createScenario() }
    }

    fun onNavigated() = _state.update { it.copy(createdScenarioId = null) }

    private suspend fun createScenario() {
        val current = _state.value
        val analysis = current.analysis ?: return
        _state.update { it.copy(busyMessage = "Working out the documents you need…") }
        val answers = analysis.questions.map { question ->
            FollowUpAnswer(question.id, question.text, current.answers[question.id].orEmpty().trim())
        }
        val id = repository.createScenario(current.description.trim(), analysis, answers)
        draftSync.cancelAndJoin()
        repository.deleteDraft(draftId)
        _state.update { it.copy(createdScenarioId = id) }
    }

    private fun NewScenarioUiState.toDraft() = ScenarioDraft(draftId, description, analysis, answers)

    private fun runBusy(message: String, block: suspend () -> Unit) {
        _state.update { it.copy(busyMessage = message, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Something went wrong") }
            } finally {
                _state.update { it.copy(busyMessage = null) }
            }
        }
    }
}

data class ScenarioDetailUiState(
    val scenario: Scenario? = null,
    val statuses: List<RequirementStatus> = emptyList(),
    val loaded: Boolean = false,
) {
    val foundCount: Int get() = statuses.count { it.state == RequirementState.FOUND }
}

class ScenarioDetailViewModel(
    private val scenarioId: String,
    private val scenarios: ScenarioRepository,
    vault: VaultRepository,
) : ViewModel() {
    val state: StateFlow<ScenarioDetailUiState> = combine(
        scenarios.scenario(scenarioId),
        vault.documents,
    ) { scenario, documents ->
        ScenarioDetailUiState(
            scenario = scenario,
            statuses = scenario?.let { s ->
                ChecklistEvaluator.evaluate(s.requirements, documents.map { it.toRef() }, today())
                    .sortedBy { status -> sortKey(status) }
            }.orEmpty(),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScenarioDetailUiState())

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            scenarios.delete(scenarioId)
            onDeleted()
        }
    }

    private fun sortKey(status: RequirementStatus): Int = when (status.state) {
        RequirementState.NEEDS_ACTION -> 0
        RequirementState.MISSING -> if (status.requirement.mandatory) 1 else 3
        RequirementState.FOUND -> 2
    }
}
