package ai.labs32.khaata.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.labs32.khaata.R
import ai.labs32.khaata.core.categorize.QuickCategories
import ai.labs32.khaata.core.model.Category
import ai.labs32.khaata.core.ui.components.CategoryIcons
import ai.labs32.khaata.core.ui.theme.KhaataShapeTokens
import ai.labs32.khaata.core.ui.theme.KhaataTheme

/**
 * Every category, searchable, with children shown under their parent.
 *
 * The entry screen's own row holds what this person uses most; this is where the rest lives. It
 * exists because the row it replaced showed the eight top-level categories only, which left the
 * forty-two subcategories — Groceries, Cab, Electricity, the ones people actually mean —
 * unreachable while entering a transaction at all.
 *
 * A vertical list rather than the horizontal strip it replaces: a row shows three or four chips
 * at a time and gives no sense of what else is there, so finding anything means scrolling
 * sideways and hoping. A list shows a dozen at a glance, and search skips the looking entirely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    categories: List<Category>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Parents keep their children immediately beneath them, so the list reads as a structure
    // rather than an alphabetical jumble in which "Cab" sits nowhere near "Transport".
    val ordered = remember(categories, query) {
        val matching = QuickCategories.search(categories, query)
        val matchingIds = matching.map { it.id }.toSet()
        val parents = categories.filter { it.parentId == null }
        buildList {
            parents.forEach { parent ->
                val children = categories.filter { it.parentId == parent.id && it.id in matchingIds }
                // A parent is kept when it matches itself or when any of its children do, so a
                // child never appears orphaned under no heading.
                if (parent.id in matchingIds || children.isNotEmpty()) {
                    add(parent)
                    addAll(children)
                }
            }
            // Anything whose parent is missing or archived still has to be reachable.
            addAll(matching.filter { it.parentId != null && parents.none { p -> p.id == it.parentId } })
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = KhaataTheme.spacing.screenHorizontal)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.category_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(KhaataTheme.spacing.small))

            if (ordered.isEmpty()) {
                Text(
                    text = stringResource(R.string.category_search_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = KhaataTheme.spacing.large),
                )
            }

            LazyColumn(
                Modifier.heightIn(max = 480.dp),
                contentPadding = PaddingValues(bottom = KhaataTheme.spacing.xlarge),
            ) {
                items(ordered, key = { it.id }) { category ->
                    CategoryRow(
                        category = category,
                        isSelected = category.id == selectedId,
                        onClick = { onSelect(category.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: Category, isSelected: Boolean, onClick: () -> Unit) {
    val isChild = category.parentId != null

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                // Children are indented rather than labelled, which is the cheapest way to show
                // the relationship without spending a line of text on it.
                start = if (isChild) KhaataTheme.spacing.large else 0.dp,
                top = 10.dp,
                bottom = 10.dp,
            )
            .heightIn(min = KhaataTheme.spacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (isChild) 32.dp else 36.dp)
                .clip(KhaataShapeTokens.cardCompact)
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = CategoryIcons[category.iconKey],
                contentDescription = null,
                modifier = Modifier.size(if (isChild) 18.dp else 20.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Spacer(Modifier.width(KhaataTheme.spacing.default))

        Text(
            text = category.name,
            style = if (isChild) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.titleSmall
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
