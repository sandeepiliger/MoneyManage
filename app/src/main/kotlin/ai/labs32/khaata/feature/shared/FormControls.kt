package ai.labs32.khaata.feature.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Form controls shared by the recurring, subscription and product editors.
 *
 * These forms all ask the same handful of questions — when, how often, from which account — so
 * they share the widgets rather than each growing a slightly different chip row.
 */

/**
 * A tappable date field.
 *
 * The date is always shown, never an empty box with a placeholder: a recurring rule with no start
 * date is not a meaningful thing, so the field opens pre-filled with a sensible default and the
 * user adjusts it. [onClear] makes the field optional, which is how "ends on" is expressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.date_field_none),
    onClear: (() -> Unit)? = null,
    error: String? = null,
) {
    var showPicker by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy") }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(KhaataShapeTokens.cardCompact)
                .clickable { showPicker = true }
                .heightIn(min = KhaataTheme.spacing.touchTarget)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CalendarToday,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = date?.format(formatter) ?: placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = if (date == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (onClear != null && date != null) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = stringResource(R.string.action_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (showPicker) {
        // The picker works in UTC millis; the conversion is pinned to UTC on both sides so a
        // user east of Greenwich cannot pick the 1st and get the 31st back.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onPick(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                        showPicker = false
                    },
                ) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * A labelled, horizontally scrolling row of single-choice chips.
 *
 * Chips rather than a dropdown because the options are short and few, and a chip row shows what
 * is available without a tap. Selection is carried by the chip's own selected state, which Compose
 * reports to screen readers, so it never depends on the tint alone.
 */
@Composable
fun <T> ChipSelector(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionKey: (T) -> Any = { it.toString() },
    error: String? = null,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(KhaataTheme.spacing.small)) {
            items(options, key = optionKey) { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option), maxLines = 1) },
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** A row with a title, an explanation and a trailing control. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = KhaataTheme.spacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(KhaataTheme.spacing.small))
        control()
    }
}
