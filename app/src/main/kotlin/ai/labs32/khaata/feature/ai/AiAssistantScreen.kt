package ai.labs32.khaata.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ai.AiAnswer
import ai.labs32.khaata.core.ai.AnswerSource
import ai.labs32.khaata.core.money.MoneyFormatter
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTheme

/**
 * The financial assistant.
 *
 * A question-and-answer surface, not a chat that can act. Every answer shows the figures it was
 * derived from and where it was computed, and there is no path from this screen to a written
 * transaction — a point the UI states rather than assumes the user will infer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    onBack: () -> Unit,
    onUpgrade: () -> Unit,
    viewModel: AiAssistantViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(state.exchanges.size) {
        if (state.exchanges.isNotEmpty()) listState.animateScrollToItem(state.exchanges.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.ai_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ProviderNotice(
                providerName = state.providerName,
                isCloud = state.isUsingCloud,
                cloudBlockedReason = state.cloudBlockedReason,
                onUpgrade = onUpgrade,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(KhaataTheme.spacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.medium),
            ) {
                if (state.exchanges.isEmpty()) {
                    item {
                        Suggestions(
                            suggestions = state.suggestions,
                            onSelect = viewModel::ask,
                        )
                    }
                }

                items(state.exchanges, key = { it.id }) { exchange ->
                    ExchangeBlock(exchange)
                }

                if (state.isThinking) {
                    item {
                        Text(
                            text = stringResource(R.string.ai_thinking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            QuestionInput(
                value = state.input,
                enabled = !state.isThinking,
                onValueChange = viewModel::onInputChange,
                onSend = { viewModel.ask(state.input) },
            )
        }
    }
}

/**
 * States which provider is answering and whether anything leaves the device.
 *
 * Always visible rather than tucked into settings: "is this sending my spending somewhere?" is
 * the first question a reasonable person has about a feature like this.
 */
@Composable
private fun ProviderNotice(
    providerName: String,
    isCloud: Boolean,
    cloudBlockedReason: String?,
    onUpgrade: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KhaataTheme.spacing.screenHorizontal)
            .padding(top = 8.dp)
            .clip(KhaataShapeTokens.cardCompact)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (isCloud) {
                    stringResource(R.string.ai_source_cloud)
                } else {
                    stringResource(R.string.ai_cloud_disabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.ai_never_acts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cloudBlockedReason?.contains("AI Pro") == true) {
            androidx.compose.material3.TextButton(onClick = onUpgrade) {
                Text(stringResource(R.string.action_learn_more))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Suggestions(suggestions: List<String>, onSelect: (String) -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.ai_suggestions),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))
        suggestions.forEach { suggestion ->
            AssistChip(
                onClick = { onSelect(suggestion) },
                label = { Text(suggestion) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ExchangeBlock(exchange: AiExchange) {
    Column(Modifier.fillMaxWidth()) {
        // The question, aligned right like a sent message.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = exchange.question,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(KhaataShapeTokens.cardCompact)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        Spacer(Modifier.height(KhaataTheme.spacing.small))

        when (val answer = exchange.answer) {
            is AiAnswer.Answered -> AnsweredBlock(answer)

            is AiAnswer.NoData -> Text(
                text = answer.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is AiAnswer.NotUnderstood -> Column {
                Text(
                    text = stringResource(R.string.ai_not_understood),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                answer.suggestions.take(3).forEach {
                    Text(
                        text = "• $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is AiAnswer.Unavailable -> Text(
                text = answer.reason,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )

            null -> Unit
        }
    }
}

@Composable
private fun AnsweredBlock(answer: AiAnswer.Answered) {
    Column {
        Text(
            text = answer.summary,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (answer.evidence.isNotEmpty()) {
            Spacer(Modifier.height(KhaataTheme.spacing.small))
            // The working, always shown rather than hidden behind a tap. An unverifiable claim
            // about someone's own money is not worth making.
            KhaataCard(contentPadding = PaddingValues(12.dp)) {
                Text(
                    text = stringResource(R.string.ai_evidence_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                answer.evidence.forEach { evidence ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                    ) {
                        Text(
                            text = evidence.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = MoneyFormatter.plain(evidence.amount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                when (answer.source) {
                    AnswerSource.ON_DEVICE -> R.string.ai_source_on_device
                    AnswerSource.CLOUD_ASSISTED -> R.string.ai_source_cloud
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuestionInput(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(KhaataTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ai_input_hint)) },
            enabled = enabled,
            maxLines = 3,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onSend,
            enabled = enabled && value.isNotBlank(),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.action_next),
                tint = if (enabled && value.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
