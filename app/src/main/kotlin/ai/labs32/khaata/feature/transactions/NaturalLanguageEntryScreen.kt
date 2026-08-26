package ai.labs32.khaata.feature.transactions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ui.components.EmptyState
import ai.labs32.khaata.core.ui.components.KhaataCard
import ai.labs32.khaata.core.ui.components.TransactionAmountText
import ai.labs32.khaata.core.ui.theme.KhaataTextStyles
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import kotlinx.coroutines.launch

/**
 * Natural-language transaction entry.
 *
 * The user describes what they spent and reviews the drafts before anything is written. The
 * confirmation step is not optional and not skippable: a parser that writes directly to the
 * ledger would eventually put a wrong amount in someone's records, and they would have no idea
 * where it came from.
 *
 * Parsing runs on-device, so this works with no network and sends nothing anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NaturalLanguageEntryScreen(
    onDone: () -> Unit,
    startListening: Boolean = false,
    viewModel: NaturalLanguageEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedCount) { if (state.savedCount > 0) onDone() }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val voicePrompt = stringResource(R.string.voice_prompt)
    val voiceUnavailable = stringResource(R.string.voice_unavailable)

    // A speech-recognizer *activity* rather than the SpeechRecognizer API: the system app owns
    // the microphone and its own permission, so this app never needs RECORD_AUDIO.
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
        if (spoken != null) {
            val combined = if (state.input.isBlank()) spoken else state.input.trimEnd() + " " + spoken
            viewModel.onInputChange(combined)
        }
    }

    // Explicitly typed: the catch branch evaluates to a Job, so without this the lambda infers
    // () -> Any and no longer satisfies IconButton's onClick.
    val launchVoiceInput: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            // No recogniser installed. The typed field behind this dialog still works, so say so
            // and leave the user on it rather than bouncing them back.
            coroutineScope.launch { snackbarHostState.showSnackbar(voiceUnavailable) }
        }
    }

    // Opened from the microphone button, so start listening without a second tap.
    //
    // Guarded by a saveable flag rather than the parameter alone: this must fire once per visit,
    // not again on every recomposition, and not again after a rotation or a process death that
    // restores this screen -- either would reopen the recogniser over whatever the user had
    // already dictated. Cancelling the recogniser leaves them here with the text field, which is
    // the same screen the manual path reaches.
    var hasAutoListened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startListening) {
        if (startListening && !hasAutoListened) {
            hasAutoListened = true
            launchVoiceInput()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.quick_add_nl_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = KhaataTheme.spacing.screenHorizontal),
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                label = { Text(stringResource(R.string.quick_add_nl_title)) },
                placeholder = { Text(stringResource(R.string.quick_add_nl_hint)) },
                trailingIcon = {
                    // Still here for a second sentence, or for when the user arrived by typing.
                    IconButton(onClick = launchVoiceInput) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = stringResource(R.string.voice_input),
                        )
                    }
                },
                minLines = 3,
            )

            Spacer(Modifier.height(KhaataTheme.spacing.small))

            // The assistant's boundary is stated on the screen where it matters, not buried in
            // a settings page.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.ai_cloud_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(KhaataTheme.spacing.default))

            when {
                state.input.isBlank() -> EmptyState(
                    icon = Icons.Outlined.EditNote,
                    title = stringResource(R.string.quick_add_nl_title),
                    description = stringResource(R.string.quick_add_nl_hint),
                )

                state.drafts.isEmpty() -> Text(
                    text = stringResource(R.string.quick_add_nl_none_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> DraftList(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun DraftList(
    state: NaturalLanguageEntryUiState,
    viewModel: NaturalLanguageEntryViewModel,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.quick_add_nl_confirm),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(KhaataTheme.spacing.small))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small),
        ) {
            items(state.drafts, key = { it.id }) { draft ->
                DraftCard(
                    draft = draft,
                    state = state,
                    onToggle = { viewModel.toggleDraft(draft.id) },
                    onCategoryChange = { viewModel.setDraftCategory(draft.id, it) },
                    onAccountChange = { viewModel.setDraftAccount(draft.id, it) },
                )
            }
        }

        Spacer(Modifier.height(KhaataTheme.spacing.small))

        Button(
            onClick = viewModel::saveSelected,
            enabled = state.selectedCount > 0 && !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Text(
                stringResource(R.string.quick_add_nl_count, state.selectedCount) +
                    " · " + stringResource(R.string.action_save),
            )
        }

        Spacer(Modifier.height(KhaataTheme.spacing.default))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftCard(
    draft: TransactionDraft,
    state: NaturalLanguageEntryUiState,
    onToggle: () -> Unit,
    onCategoryChange: (String) -> Unit,
    onAccountChange: (String) -> Unit,
) {
    KhaataCard(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = draft.isSelected,
                onCheckedChange = { onToggle() },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = draft.merchantDisplayName
                        ?: stringResource(R.string.categories_uncategorised),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = draft.sourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TransactionAmountText(
                amount = draft.amount,
                type = draft.type,
                style = KhaataTextStyles.amountMedium,
            )
        }

        if (draft.needsReview) {
            Spacer(Modifier.height(KhaataTheme.spacing.small))
            Text(
                text = stringResource(R.string.sms_review_check_fields),
                style = MaterialTheme.typography.labelSmall,
                color = KhaataTheme.money.warning,
            )
        }

        Spacer(Modifier.height(KhaataTheme.spacing.small))

        // Filtered once per change rather than rebuilt on every keystroke in the draft above.
        val topLevelCategories = remember(state.categories) {
            state.categories.filter { it.parentId == null }
        }

        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(topLevelCategories, key = { it.id }) { category ->
                FilterChip(
                    selected = category.id == draft.categoryId,
                    onClick = { onCategoryChange(category.id) },
                    label = { Text(category.name, maxLines = 1) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.accounts, key = { it.id }) { account ->
                FilterChip(
                    selected = account.id == draft.accountId,
                    onClick = { onAccountChange(account.id) },
                    label = { Text(account.name, maxLines = 1) },
                )
            }
        }
    }
}
