package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.hook.contract.DefaultHookTimeoutSeconds
import io.github.stream29.kodex.hook.contract.HookCommandDefinition
import io.github.stream29.kodex.hook.contract.HookDeclarations
import io.github.stream29.kodex.hook.contract.HookEnvironmentDraft
import io.github.stream29.kodex.hook.contract.HookEvent
import io.github.stream29.kodex.hook.contract.HookImportDecision
import io.github.stream29.kodex.hook.contract.HookImportDisposition
import io.github.stream29.kodex.hook.contract.HookImportItem
import io.github.stream29.kodex.hook.contract.HookImportPreview
import io.github.stream29.kodex.hook.contract.HookImportSupport
import io.github.stream29.kodex.hook.contract.HookManagedSourceState
import io.github.stream29.kodex.hook.contract.HookMatcher
import io.github.stream29.kodex.hook.contract.HookMatcherGroup
import io.github.stream29.kodex.hook.contract.HookSourceDraft

internal data class HookEditorRequest(
    val sourceId: String? = null,
    val draft: HookSourceDraft? = null,
)

/** Add/edit form that never receives stored environment values. */
@Composable
internal fun BoxScope.HookSourceEditorDialog(
    request: HookEditorRequest,
    onDismiss: () -> Unit,
    onSave: (HookSourceDraft) -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, HookEditorMaximumWidth)
    val initial = request.draft
    val name = rememberHookInput(initial?.name.orEmpty())
    val environment = rememberHookInput(initial?.environment?.formatEnvironmentDrafts().orEmpty())
    var sourceEnabled by remember(request) { mutableStateOf(initial?.enabled ?: true) }
    var entries by remember(request) {
        mutableStateOf(
            initial?.hooks
                ?.toEditorEntries()
                ?.takeIf(List<*>::isNotEmpty)
                ?: listOf(newHookEditorEntry()),
        )
    }
    var error by remember(request) { mutableStateOf<String?>(null) }

    fun save() {
        runCatching {
            val sourceName = name.value.text.trim()
            require(sourceName.isNotEmpty()) { "Source name is required." }
            require(entries.isNotEmpty()) { "At least one command is required." }
            var declarations = HookDeclarations()
            entries.forEach { entry ->
                val matcher = HookMatcher.parse(entry.matcher.value.text)
                require(matcher !is HookMatcher.Invalid) {
                    "Every matcher must be a valid pattern."
                }
                val command = entry.command.value.text
                require(command.isNotBlank()) { "Every command must be non-blank." }
                val timeout = entry.timeout.value.text.trim().toLongOrNull()
                    ?: throw IllegalArgumentException("Every timeout must be a whole number.")
                require(timeout >= 1L) { "Every timeout must be at least one second." }
                val additionalContextLimit = entry.additionalContextLimit.value.text
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.toIntOrNull()
                    ?: if (entry.additionalContextLimit.value.text.isBlank()) {
                        null
                    } else {
                        throw IllegalArgumentException(
                            "Every context limit must be a whole number.",
                        )
                    }
                val group = HookMatcherGroup(
                    matcher = matcher,
                    hooks = listOf(
                        HookCommandDefinition(
                            command = command,
                            timeoutSeconds = timeout,
                            enabled = entry.enabled,
                            statusMessage = entry.statusMessage.value.text
                                .trim()
                                .takeIf(String::isNotEmpty),
                            additionalContextLimit = additionalContextLimit,
                        ),
                    ),
                )
                declarations = declarations.withGroups(
                    event = entry.event,
                    groups = declarations.groups(entry.event) + group,
                )
            }
            HookSourceDraft(
                name = sourceName,
                enabled = sourceEnabled,
                environment = parseHookEnvironment(
                    text = environment.value.text,
                    allowKeep = request.sourceId != null,
                ),
                hooks = declarations,
            )
        }.fold(
            onSuccess = onSave,
            onFailure = { failure ->
                error = failure.message ?: "The Hook source configuration is invalid."
            },
        )
    }

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                value = if (request.sourceId == null) "Add Hook source" else "Edit Hook source",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            HookInputField("Source name", name, width, autoFocus = true)
            Text("Source state", color = SettingsForeground)
            Row {
                listOf(true, false).forEachIndexed { index, enabled ->
                    if (index > 0) Text(" ")
                    TuiButton(
                        label = if (enabled) "Enabled" else "Disabled",
                        modifier = Modifier.background(SettingsHomeBackground),
                        color = SettingsForeground,
                        selected = enabled == sourceEnabled,
                        onClick = { sourceEnabled = enabled },
                    )
                }
            }
            HookInputField(
                label = "Environment (KEY=value; use <keep> for stored values)",
                state = environment,
                width = width,
            )
            entries.forEachIndexed { index, entry ->
                Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
                    Text("Command ${index + 1}", color = SettingsForeground)
                    Row {
                        Text("Event: ", color = SettingsForeground)
                        TuiButton(
                            label = entry.event.settingsLabel(),
                            color = SettingsForeground,
                            onClick = {
                                entries = entries.replace(
                                    index,
                                    entry.copy(event = entry.event.next()),
                                )
                            },
                        )
                        Text(" ")
                        TuiButton(
                            label = if (entry.enabled) "Enabled" else "Disabled",
                            color = SettingsForeground,
                            onClick = {
                                entries = entries.replace(
                                    index,
                                    entry.copy(enabled = !entry.enabled),
                                )
                            },
                        )
                        Text(" ")
                        TuiButton(
                            label = "Remove",
                            color = SettingsForeground,
                            enabled = entries.size > 1,
                            onClick = {
                                entries = entries.filterIndexed { candidate, _ ->
                                    candidate != index
                                }
                            },
                        )
                    }
                    HookInputField("Matcher", entry.matcher, width)
                    HookInputField("Command", entry.command, width)
                    HookInputField("Timeout seconds", entry.timeout, width)
                    HookInputField("Status message (optional)", entry.statusMessage, width)
                    HookInputField(
                        "Additional context limit (optional)",
                        entry.additionalContextLimit,
                        width,
                    )
                }
            }
            TuiButton(
                label = "Add command",
                color = SettingsForeground,
                onClick = { entries = entries + newHookEditorEntry() },
            )
            error?.let { message ->
                Text(message, color = SettingsForeground, textStyle = TextStyle.Bold)
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(label = "Cancel", color = SettingsForeground, onClick = onDismiss)
                TuiButton(label = "Save", color = SettingsForeground, onClick = ::save)
            }
        }
    }
}

/** Sanitized Hook source details and source-level commands. */
@Composable
internal fun BoxScope.HookSourceDetailsDialog(
    source: HookManagedSourceState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetEnabled: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, HookDetailsMaximumWidth)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                value = source.name,
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            HookDetailLine("State", if (source.enabled) "Enabled" else "Disabled")
            HookDetailLine(
                "Commands",
                "${source.commandCount} " +
                    if (source.commandCount == 1) "command" else "commands",
            )
            if (source.configuredEvents.isNotEmpty()) {
                HookDetailLine(
                    "Events",
                    source.configuredEvents.joinToString { event -> event.settingsLabel() },
                )
            }
            if (source.environmentNames.isNotEmpty()) {
                HookDetailLine(
                    "Environment",
                    "${source.environmentNames.joinToString()} (values hidden)",
                )
            }
            source.importedFrom?.let { imported ->
                HookDetailLine(
                    "Imported from",
                    "${imported.sourceKind.settingsLabel()}: ${imported.normalizedPath}",
                )
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(label = "Close", color = SettingsForeground, onClick = onDismiss)
                TuiButton(
                    label = if (source.enabled) "Disable" else "Enable",
                    color = SettingsForeground,
                    onClick = onSetEnabled,
                )
                TuiButton(label = "Edit", color = SettingsForeground, onClick = onEdit)
                TuiButton(
                    label = "Delete",
                    color = TuiTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun HookDetailLine(label: String, value: String) {
    Text(
        value = "$label: $value",
        color = SettingsForeground,
        textStyle = TextStyle.Dim,
    )
}

@Composable
internal fun BoxScope.HookDeleteConfirmationDialog(
    source: HookManagedSourceState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val width = (LocalTerminalState.current.size.columns - 4)
        .coerceIn(1, HookDeleteMaximumWidth)
    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                "Delete Hook source",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Text("Delete '${source.name}'?", color = SettingsForeground)
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(
                    label = "Cancel",
                    color = SettingsForeground,
                    autoFocus = true,
                    onClick = onDismiss,
                )
                TuiButton(
                    label = "Delete",
                    color = TuiTheme.colorScheme.error,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/** Direct selection flow; opening the dialog starts preview loading before it is rendered. */
@Composable
internal fun BoxScope.HookImportDialog(
    preview: HookImportPreview?,
    onApply: (Long, Map<String, HookImportDecision>) -> Unit,
    onDismiss: () -> Unit,
) {
    val terminalSize = LocalTerminalState.current.size
    val width = (terminalSize.columns - 4)
        .coerceIn(1, HookImportMaximumWidth)
    val height = (terminalSize.rows - 4)
        .coerceIn(1, HookImportMaximumHeight)
    val scrollState = remember(preview?.id) { ScrollState() }
    var decisions by remember(preview?.id) {
        mutableStateOf(preview?.defaultImportDecisions().orEmpty())
    }
    val selectedCount = decisions.values.count { decision ->
        decision != HookImportDecision.Skip
    }

    TuiDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(width).height(height).background(SettingsHomeBackground),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SettingsHomeBackground)) {
            Text(
                "Import Hooks from Codex",
                modifier = Modifier.fillMaxWidth().background(SettingsHeaderBackground),
                color = SettingsForeground,
                textStyle = TuiTheme.typography.headline,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
            ) {
                if (preview == null) {
                    Text("Loading Codex Hook sources…", color = SettingsForeground)
                } else {
                    Text(
                        "All supported sources are selected. Select a source to toggle it.",
                        color = SettingsForeground,
                        textStyle = TextStyle.Dim,
                    )
                    Row {
                        TuiButton(
                            label = "Select all",
                            color = SettingsForeground,
                            enabled = preview.items.any { item -> item.selectable },
                            onClick = { decisions = preview.defaultImportDecisions() },
                        )
                        Text(" ")
                        TuiButton(
                            label = "Clear",
                            color = SettingsForeground,
                            enabled = selectedCount > 0,
                            onClick = {
                                decisions = preview.items.associate { item ->
                                    item.sourceKey to HookImportDecision.Skip
                                }
                            },
                        )
                    }
                    if (preview.items.isEmpty()) {
                        Text("No Codex Hook sources found.", color = SettingsForeground)
                    }
                    preview.items.forEach { item ->
                        val decision = decisions[item.sourceKey] ?: HookImportDecision.Skip
                        val marker = when {
                            !item.selectable -> "–"
                            decision == HookImportDecision.Skip -> " "
                            else -> "✓"
                        }
                        TuiButton(
                            label = "$marker ${item.displayName} · ${item.importLabel()}",
                            modifier = Modifier.fillMaxWidth(),
                            color = SettingsForeground,
                            enabled = item.selectable,
                            onClick = {
                                decisions = decisions + (
                                    item.sourceKey to decision.nextFor(item)
                                    )
                            },
                        )
                        Text(
                            value = "  ${item.sourceKind.settingsLabel()}: ${item.normalizedPath}",
                            color = SettingsForeground,
                            textStyle = TextStyle.Dim,
                        )
                        Text(
                            value = "  ${item.commandCount} commands; " +
                                item.configuredEvents.joinToString { event -> event.settingsLabel() },
                            color = SettingsForeground,
                            textStyle = TextStyle.Dim,
                        )
                        if (item.environmentNames.isNotEmpty()) {
                            Text(
                                value = "  Environment: ${item.environmentNames.joinToString()} " +
                                    "(values hidden)",
                                color = SettingsForeground,
                                textStyle = TextStyle.Dim,
                            )
                        }
                        item.excludedDetails.forEach { detail ->
                            Text(
                                value = "  $detail",
                                color = SettingsForeground,
                                textStyle = TextStyle.Dim,
                            )
                        }
                    }
                }
            }
            TuiDialogActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsActionBackground),
            ) {
                TuiButton(label = "Cancel", color = SettingsForeground, onClick = onDismiss)
                TuiButton(
                    label = "Import selected ($selectedCount)",
                    color = SettingsForeground,
                    enabled = preview != null && selectedCount > 0,
                    onClick = {
                        preview?.let { current -> onApply(current.id, decisions) }
                    },
                )
            }
        }
    }
}

internal fun HookImportPreview.defaultImportDecisions(): Map<String, HookImportDecision> =
    items.associate { item ->
        item.sourceKey to when {
            !item.selectable -> HookImportDecision.Skip
            item.disposition == HookImportDisposition.New -> HookImportDecision.Import
            item.disposition == HookImportDisposition.Conflict -> HookImportDecision.Replace
            else -> HookImportDecision.Skip
        }
    }

@Composable
private fun HookInputField(
    label: String,
    state: TextInputState,
    width: Int,
    autoFocus: Boolean = false,
) {
    Text(label, color = SettingsForeground)
    TextInput(
        state = state,
        layout = TextInputLayout.create(state.value, width),
        modifier = Modifier.fillMaxWidth(),
        autoFocus = autoFocus,
    )
}

@Composable
private fun rememberHookInput(initialValue: String = ""): TextInputState =
    remember(initialValue) {
        TextInputState(
            TextInputValue(
                text = initialValue,
                cursorOffset = initialValue.length,
            ),
        )
    }

private data class HookEditorEntry(
    val event: HookEvent,
    val matcher: TextInputState,
    val command: TextInputState,
    val timeout: TextInputState,
    val statusMessage: TextInputState,
    val additionalContextLimit: TextInputState,
    val enabled: Boolean,
)

private fun newHookEditorEntry(): HookEditorEntry =
    hookEditorEntry(
        event = HookEvent.PreToolUse,
        matcher = "*",
        definition = HookCommandDefinition(
            command = "echo",
            timeoutSeconds = DefaultHookTimeoutSeconds,
        ),
    )

private fun HookDeclarations.toEditorEntries(): List<HookEditorEntry> =
    buildList {
        HookEvent.entries.forEach { event ->
            groups(event).forEach { group ->
                group.hooks.forEach { command ->
                    add(
                        hookEditorEntry(
                            event = event,
                            matcher = group.matcher.pattern,
                            definition = command,
                        ),
                    )
                }
            }
        }
    }

private fun hookEditorEntry(
    event: HookEvent,
    matcher: String,
    definition: HookCommandDefinition,
): HookEditorEntry =
    HookEditorEntry(
        event = event,
        matcher = inputState(matcher),
        command = inputState(definition.command),
        timeout = inputState(definition.timeoutSeconds.toString()),
        statusMessage = inputState(definition.statusMessage.orEmpty()),
        additionalContextLimit = inputState(definition.additionalContextLimit?.toString().orEmpty()),
        enabled = definition.enabled,
    )

private fun inputState(value: String): TextInputState =
    TextInputState(TextInputValue(text = value, cursorOffset = value.length))

private fun List<HookEditorEntry>.replace(
    index: Int,
    value: HookEditorEntry,
): List<HookEditorEntry> =
    toMutableList().apply { set(index, value) }

private fun HookEvent.next(): HookEvent =
    HookEvent.entries[(ordinal + 1) % HookEvent.entries.size]

private fun Map<String, HookEnvironmentDraft>.formatEnvironmentDrafts(): String =
    entries.joinToString(";") { (name, draft) ->
        "$name=" + when (draft) {
            HookEnvironmentDraft.Keep -> KeepHookEnvironmentMarker
            is HookEnvironmentDraft.Replace -> draft.value
        }
    }

private fun parseHookEnvironment(
    text: String,
    allowKeep: Boolean,
): Map<String, HookEnvironmentDraft> =
    buildMap {
        text
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { entry ->
                val separator = entry.indexOf('=')
                require(separator > 0) { "Each environment entry must use KEY=value." }
                val name = entry.substring(0, separator).trim()
                val value = entry.substring(separator + 1)
                require(name.isNotEmpty()) { "An environment name must not be blank." }
                require(name !in this) { "Environment names must be unique." }
                put(
                    name,
                    if (value == KeepHookEnvironmentMarker) {
                        require(allowKeep) {
                            "$KeepHookEnvironmentMarker is valid only while editing."
                        }
                        HookEnvironmentDraft.Keep
                    } else {
                        HookEnvironmentDraft.Replace(value)
                    },
                )
            }
    }

private fun HookImportItem.importLabel(): String =
    buildString {
        append(
            when (disposition) {
                HookImportDisposition.New -> "New"
                HookImportDisposition.Conflict -> "Replace existing"
                null -> "Unsupported"
            },
        )
        if (support == HookImportSupport.Partial) append(" · partial")
    }

private fun HookImportDecision.nextFor(item: HookImportItem): HookImportDecision =
    when (this) {
        HookImportDecision.Skip -> when (item.disposition) {
            HookImportDisposition.New -> HookImportDecision.Import
            HookImportDisposition.Conflict -> HookImportDecision.Replace
            null -> HookImportDecision.Skip
        }

        HookImportDecision.Import,
        HookImportDecision.Replace,
            -> HookImportDecision.Skip
    }

private const val KeepHookEnvironmentMarker: String = "<keep>"
private const val HookEditorMaximumWidth: Int = 96
private const val HookDetailsMaximumWidth: Int = 84
private const val HookDeleteMaximumWidth: Int = 56
private const val HookImportMaximumWidth: Int = 96
private const val HookImportMaximumHeight: Int = 30
