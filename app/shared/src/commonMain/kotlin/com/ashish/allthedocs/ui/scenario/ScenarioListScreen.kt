package com.ashish.allthedocs.ui.scenario

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashish.allthedocs.api.CuratedScenarioSummary
import com.ashish.allthedocs.data.Scenario
import com.ashish.allthedocs.data.ScenarioDraft
import com.ashish.allthedocs.ui.AppTopBar
import com.ashish.allthedocs.ui.StatusChip
import com.ashish.allthedocs.ui.Tone
import com.ashish.allthedocs.ui.label
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ScenarioListScreen(
    onNewScenario: () -> Unit,
    onQuickStart: (String) -> Unit,
    onContinueDraft: (String) -> Unit,
    onOpenScenario: (String) -> Unit,
    viewModel: ScenarioListViewModel = koinViewModel(),
) {
    val scenarios by viewModel.scenarios.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val quickStart by viewModel.quickStart.collectAsStateWithLifecycle()
    ScenarioListContent(
        scenarios = scenarios,
        drafts = drafts,
        quickStart = quickStart,
        onNewScenario = onNewScenario,
        onQuickStart = onQuickStart,
        onRetryQuickStart = viewModel::loadQuickStart,
        onContinueDraft = onContinueDraft,
        onDiscardDraft = viewModel::discardDraft,
        onOpenScenario = onOpenScenario,
    )
}

@Composable
private fun ScenarioListContent(
    scenarios: List<Scenario>?,
    drafts: List<ScenarioDraft>,
    quickStart: QuickStartState,
    onNewScenario: () -> Unit,
    onQuickStart: (String) -> Unit,
    onRetryQuickStart: () -> Unit,
    onContinueDraft: (String) -> Unit,
    onDiscardDraft: (String) -> Unit,
    onOpenScenario: (String) -> Unit,
) {
    Scaffold(
        topBar = { AppTopBar("AllTheDocs") },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewScenario,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New scenario") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionHeader("Common scenarios")
            }
            item {
                QuickStartSection(quickStart, onQuickStart, onRetryQuickStart)
            }
            if (drafts.isNotEmpty()) {
                item {
                    SectionHeader("Continue where you left off")
                }
                items(drafts, key = { "draft-" + it.id }) { draft ->
                    DraftCard(draft, onContinue = { onContinueDraft(draft.id) }, onDiscard = { onDiscardDraft(draft.id) })
                }
            }
            item {
                SectionHeader("Your scenarios")
            }
            when {
                scenarios == null -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                scenarios.isEmpty() -> item {
                    Text(
                        "Pick a common scenario above, or tap New scenario and describe what you're getting ready for.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                else -> items(scenarios, key = { it.id }) { scenario ->
                    ScenarioCard(scenario, onOpenScenario)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun QuickStartSection(
    state: QuickStartState,
    onQuickStart: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        QuickStartState.Loading -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        is QuickStartState.Failed -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        is QuickStartState.Loaded -> LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.scenarios, key = { it.id }) { scenario ->
                QuickStartCard(scenario, onClick = { onQuickStart(scenario.id) })
            }
        }
    }
}

@Composable
private fun QuickStartCard(scenario: CuratedScenarioSummary, onClick: () -> Unit) {
    ElevatedCard(Modifier.width(220.dp).height(150.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(scenario.countryCode, Tone.Neutral)
            Text(
                scenario.title.removeSuffix(" (India)"),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                scenario.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DraftCard(draft: ScenarioDraft, onContinue: () -> Unit, onDiscard: () -> Unit) {
    val analysis = draft.analysis
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onContinue)) {
        Row(
            Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(analysis?.title ?: "New scenario", style = MaterialTheme.typography.titleMedium)
                if (draft.description.isNotBlank()) {
                    Text(
                        draft.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when {
                        analysis == null -> "Draft · not analysed yet"
                        analysis.questions.isEmpty() -> "Draft · ready to continue"
                        else -> "Draft · ${draft.answeredCount} of ${analysis.questions.size} questions answered"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDiscard) {
                Icon(Icons.Default.Delete, contentDescription = "Discard draft")
            }
        }
    }
}

@Composable
private fun ScenarioCard(scenario: Scenario, onOpenScenario: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpenScenario(scenario.id) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(scenario.title, style = MaterialTheme.typography.titleMedium)
            Text(
                scenario.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(
                    "${scenario.requirements.size} documents",
                    scenario.countryCode,
                    scenario.origin.label(),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
