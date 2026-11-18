package com.ashish.allthedocs.ui.scenario

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ashish.allthedocs.api.FollowUpQuestion
import com.ashish.allthedocs.ui.AppTopBar
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun NewScenarioScreen(
    draftId: String,
    curatedScenarioId: String,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: NewScenarioViewModel = koinViewModel(key = "new-$draftId") { parametersOf(draftId, curatedScenarioId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdScenarioId) {
        state.createdScenarioId?.let {
            viewModel.onNavigated()
            onCreated(it)
        }
    }

    NewScenarioContent(
        state = state,
        onBack = onBack,
        onDescriptionChange = viewModel::onDescriptionChange,
        onAnalyze = viewModel::analyze,
        onAnswer = viewModel::onAnswer,
        onEditDescription = viewModel::editDescription,
        onSubmit = viewModel::submit,
    )
}

@Composable
private fun NewScenarioContent(
    state: NewScenarioUiState,
    onBack: () -> Unit,
    onDescriptionChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onAnswer: (String, String) -> Unit,
    onEditDescription: () -> Unit,
    onSubmit: () -> Unit,
) {
    Scaffold(topBar = { AppTopBar("New scenario", onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val analysis = state.analysis
            if (analysis == null) {
                Text("Describe what you're trying to do", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.description,
                    onValueChange = onDescriptionChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = { Text("e.g. I'm renewing my Indian passport, which expires next month") },
                    enabled = state.busyMessage == null,
                )
                Button(onClick = onAnalyze, enabled = state.canAnalyze, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue")
                }
            } else {
                Text(analysis.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    state.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onEditDescription, enabled = state.busyMessage == null) {
                    Text("Edit description")
                }
                if (analysis.questions.isNotEmpty()) {
                    Text("A few quick questions", style = MaterialTheme.typography.titleMedium)
                    analysis.questions.forEach { question ->
                        QuestionField(
                            question = question,
                            answer = state.answers[question.id].orEmpty(),
                            enabled = state.busyMessage == null,
                            onAnswer = { onAnswer(question.id, it) },
                        )
                    }
                    Button(onClick = onSubmit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
                        Text("Show my document checklist")
                    }
                }
            }
            state.busyMessage?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionField(
    question: FollowUpQuestion,
    answer: String,
    enabled: Boolean,
    onAnswer: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question.text, style = MaterialTheme.typography.bodyLarge)
        if (question.options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                question.options.forEach { option ->
                    FilterChip(
                        selected = answer == option,
                        onClick = { onAnswer(option) },
                        label = { Text(option) },
                        enabled = enabled,
                    )
                }
            }
        }
        if (question.allowFreeText) {
            OutlinedTextField(
                value = if (answer in question.options) "" else answer,
                onValueChange = onAnswer,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(if (question.options.isEmpty()) "Your answer" else "Or type your own") },
                enabled = enabled,
                singleLine = true,
            )
        }
    }
}
