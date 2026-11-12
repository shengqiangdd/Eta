package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.roleplay.CharacterBookEntryDraft
import io.github.mangi.eta.agent.roleplay.CharacterWorldbook
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.characterWorldbookEditor(
    store: CharacterLibraryStore,
    expanded: Boolean,
    expandedEntry: Int?,
    onToggleExpanded: () -> Unit,
    onExpandEntry: (Int?) -> Unit,
) {
    val card = store.draft ?: return
    val book = card.worldbookDraft()
    val enabled = !store.busy
    item(key = "worldbook-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_worldbook_section)) }
    item(key = "worldbook-header") {
        val entriesLabel = pluralStringResource(R.plurals.character_worldbook_entries, book.entries.size, book.entries.size)
        val toggleLabel = if (expanded) {
            stringResource(R.string.character_worldbook_collapsed)
        } else {
            stringResource(R.string.character_worldbook_expand_edit)
        }
        EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding)) {
            EtaArrowPreference(
                title = stringResource(R.string.character_worldbook_embedded),
                summary = "$entriesLabel · $toggleLabel",
                onClick = onToggleExpanded,
            )
        }
    }
    if (!expanded) return
    item(key = "worldbook-name") {
        CharacterTextField(stringResource(R.string.character_worldbook_name), book.name, { value -> store.updateWorldbook { it.copy(name = value) } }, enabled, singleLine = true)
    }
    item(key = "worldbook-depth") {
        CharacterTextField(stringResource(R.string.character_worldbook_scan_depth), book.scanDepth?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(scanDepth = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-budget") {
        CharacterTextField(stringResource(R.string.character_worldbook_budget), book.tokenBudget?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(tokenBudget = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-recursive") {
        EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp)) {
            EtaSwitchPreference(
                title = stringResource(R.string.character_worldbook_recursive),
                summary = stringResource(R.string.character_worldbook_recursive_summary),
                checked = book.recursiveScanning == true,
                enabled = enabled,
                onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
            )
        }
    }
    item(key = "worldbook-entries-title") { EtaPreferenceGroupTitle(stringResource(R.string.character_worldbook_entries_section)) }
    val unsupported = CharacterWorldbook.unsupportedEntries(card).associate { it.index to it.reasons }
    itemsIndexed(book.entries, key = { index, _ -> "worldbook-entry-$index" }) { index, entry ->
        EtaPreferenceGroup(
            modifier = Modifier
                .padding(horizontal = CharacterCardPadding, vertical = 8.dp),
        ) {
            EtaArrowPreference(
                title = entry.name.take(120).ifBlank { stringResource(R.string.character_worldbook_entry_name, index + 1) },
                summary = when {
                    !entry.enabled -> stringResource(R.string.character_worldbook_disabled)
                    !unsupported[index].isNullOrEmpty() -> stringResource(R.string.character_worldbook_skipped, unsupported[index].orEmpty().joinToString(stringResource(R.string.list_separator)))
                    entry.constant -> stringResource(R.string.character_worldbook_constant)
                    else -> entry.keys.take(4).joinToString(stringResource(R.string.list_separator)).take(160).ifBlank { stringResource(R.string.character_worldbook_no_keys) }
                },
                onClick = { onExpandEntry(if (expandedEntry == index) null else index) },
                endActions = {
                    EtaSwitch(
                        checked = entry.enabled,
                        enabled = enabled,
                        onCheckedChange = { value ->
                            store.updateWorldbook { current ->
                                current.copy(entries = current.entries.toMutableList().apply { this[index] = entry.copy(enabled = value) })
                            }
                        },
                    )
                },
            )
            if (expandedEntry == index) {
                CharacterWorldbookEntryEditor(
                    entry = entry,
                    enabled = enabled,
                    unsupported = unsupported[index].orEmpty(),
                    onChange = { replacement ->
                        store.updateWorldbook { current -> current.copy(entries = current.entries.toMutableList().apply { this[index] = replacement }) }
                    },
                    onDelete = {
                        onExpandEntry(null)
                        store.updateWorldbook { current -> current.copy(entries = current.entries.filterIndexed { i, _ -> i != index }) }
                    },
                )
            }
        }
    }
    item(key = "worldbook-add-entry") {
        EtaTextButton(
            text = stringResource(R.string.character_worldbook_add_entry),
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CharacterCardPadding, vertical = 8.dp),
            onClick = {
                onExpandEntry(book.entries.size)
                store.updateWorldbook { it.copy(entries = it.entries + CharacterBookEntryDraft(insertionOrder = it.entries.size)) }
            },
        )
    }
}

@Composable
private fun CharacterWorldbookEntryEditor(
    entry: CharacterBookEntryDraft,
    enabled: Boolean,
    unsupported: List<String>,
    onChange: (CharacterBookEntryDraft) -> Unit,
    onDelete: () -> Unit,
) {
    if (unsupported.isNotEmpty()) {
        Text(
            stringResource(R.string.character_worldbook_unsupported_note, unsupported.joinToString("；")),
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    CharacterFieldGroupLabel(stringResource(R.string.character_worldbook_content_label))
    CharacterTextField(stringResource(R.string.character_worldbook_entry_title), entry.name, { onChange(entry.copy(name = it)) }, enabled, singleLine = true)
    CharacterTextField(stringResource(R.string.character_worldbook_content_label), entry.content, { onChange(entry.copy(content = it)) }, enabled, minLines = 4)
    CharacterFieldGroupLabel(stringResource(R.string.character_worldbook_trigger_label))
    CharacterTextField(stringResource(R.string.character_worldbook_primary_keys), entry.keys.joinToString("\n"), { onChange(entry.copy(keys = it.lines())) }, enabled)
    CharacterTextField(stringResource(R.string.character_worldbook_secondary_keys), entry.secondaryKeys.joinToString("\n"), { onChange(entry.copy(secondaryKeys = it.lines())) }, enabled)
    EtaSwitchPreference(
        title = stringResource(R.string.character_worldbook_selective),
        checked = entry.selective,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(selective = it)) },
    )
    CharacterFieldGroupLabel(stringResource(R.string.character_worldbook_insert_label))
    EtaSwitchPreference(
        title = stringResource(R.string.character_worldbook_constant),
        checked = entry.constant,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(constant = it)) },
    )
    EtaSwitchPreference(
        title = stringResource(R.string.character_worldbook_before_char),
        summary = stringResource(R.string.character_worldbook_before_char_summary),
        checked = entry.position == "before_char",
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(position = if (it) "before_char" else "after_char")) },
    )
    CharacterTextField(stringResource(R.string.character_worldbook_insert_order), entry.insertionOrder.toString(), { value ->
        value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(insertionOrder = it)) }
    }, enabled, singleLine = true)
    EtaTextButton(
        text = stringResource(R.string.character_worldbook_remove_entry),
        enabled = enabled,
        onClick = onDelete,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private fun optionalNonNegativeInt(value: String, onValue: (Int?) -> Unit) {
    if (value.isBlank()) onValue(null)
    else value.toIntOrNull()?.takeIf { it >= 0 }?.let(onValue)
}
