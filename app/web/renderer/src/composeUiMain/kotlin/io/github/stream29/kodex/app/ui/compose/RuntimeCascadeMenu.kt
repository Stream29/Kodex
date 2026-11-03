package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeMenuEntry

/**
 * Render inside the trigger's Box: Popup observes that parent's window bounds.
 * An explicit, live window-pixel anchor can instead be supplied by the caller.
 * Keep owner referentially stable for the borrowed runtime child, not its menu projection.
 * Invoke even when collapsed; dismiss synchronously clears caller-owned expanded state.
 * Trigger restoration runs after layer disposal,
 * only for keyboard dismissal or selection; outside presses are consumed, not replayed.
 */
@Composable
internal fun RuntimeCascadeMenu(
    owner: Any,
    field: String,
    roots: List<RuntimeMenuEntry>,
    enabled: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    restoreTriggerFocus: () -> Unit,
    anchorBoundsInWindow: IntRect? = null,
) {
    val state = remember(field) { RuntimeCascadeMenuState() }
    val tracker = remember(field) { CascadeOpenTracker() }
    var revision by remember { mutableIntStateOf(0) }
    var restoreOwner by remember { mutableStateOf<Any?>(null) }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestRestore by rememberUpdatedState(restoreTriggerFocus)
    val available = enabled && (anchorBoundsInWindow == null ||
        (anchorBoundsInWindow.width > 0 && anchorBoundsInWindow.height > 0))
    val latestInput by rememberUpdatedState(CascadeInput(owner, roots, available, expanded))
    revision
    state.update(owner, roots, available)
    if (expanded && !tracker.expanded) state.open()
    tracker.expanded = expanded
    if (!expanded && state.isOpen) state.close()
    LaunchedEffect(expanded, state.isOpen) {
        if (expanded && !state.isOpen) latestDismiss()
    }
    LaunchedEffect(restoreOwner, state.isOpen, owner) {
        val pending = restoreOwner
        if (pending != null && !state.isOpen) {
            withFrameNanos { }
            restoreOwner = null
            if (pending === latestInput.owner && latestInput.enabled &&
                latestInput.roots.isNotEmpty() && !latestInput.expanded) latestRestore()
        }
    }
    if (!state.isOpen) return

    val position = remember { CascadeWindowPosition() }
    val columns = (0..state.path.size).map(state::entries)
    val textMeasurer = rememberTextMeasurer()
    val rowTextStyle = MaterialTheme.typography.bodyMedium
    val panels = columns.indices.map { level ->
        key(state.path.take(level)) { remember { CascadePanel() } }
    }
    val columnKeys = columns.map { entries -> entries.map { it.key } }
    var focused by remember { mutableStateOf<CascadeFocus?>(null) }
    var requested by remember { mutableStateOf<CascadeFocus?>(null) }
    fun focusInitial(level: Int, preferred: Any? = null) {
        val entries = state.entries(level)
        val entry = entries.firstOrNull { it.key == preferred }
            ?: entries.firstOrNull { it.selected } ?: entries.firstOrNull()
        requested = entry?.let { CascadeFocus(state.path.take(level), it.key) }
    }
    fun dismiss(restore: Boolean) {
        if (!state.isOpen) return
        state.close()
        revision++
        restoreOwner = if (restore) latestInput.owner else null
        latestDismiss()
    }
    fun refreshInput() {
        state.update(latestInput.owner, latestInput.roots, latestInput.enabled)
        if (!latestInput.expanded) state.close()
    }
    fun back() {
        refreshInput()
        if (!state.isOpen) { revision++; return }
        val level = focused?.parents?.size?.coerceAtMost(state.path.size) ?: state.path.size
        if (state.path.isEmpty()) dismiss(true) else {
            // At an ancestor, Escape still removes one open descendant. In a child it exits
            // that child and focuses the exact parent row, not the selected sibling.
            val exitLevel = if (level == 0) state.path.size else level
            val parentKey = state.path[exitLevel - 1]
            state.collapseTo(exitLevel - 1)
            revision++
            requested = CascadeFocus(state.path, parentKey)
        }
    }
    fun activate(target: RuntimeCascadeMenuState.Target) {
        refreshInput()
        val entry = state.resolve(target)
        if (entry == null) { revision++; return }
        try {
            state.activate(target)
        } finally {
            revision++
            if (!state.isOpen) {
                restoreOwner = latestInput.owner
                latestDismiss()
            }
        }
        if (entry is RuntimeMenuEntry.Branch) {
            focusInitial(target.path.size + 1, entry.initialChildKey)
        }
    }
    LaunchedEffect(owner, expanded) { focusInitial(0) }
    LaunchedEffect(state.path, columnKeys, requested) {
        val current = focused
        if (requested == null && (current == null ||
                current.parents != state.path.take(current.parents.size) ||
                state.entries(current.parents.size).none { it.key == current.key })) {
            focusInitial(state.path.size)
        }
    }
    LaunchedEffect(requested, state.path, columnKeys) {
        val target = requested ?: return@LaunchedEffect
        val level = target.parents.size
        if (target.parents != state.path.take(level) || level !in panels.indices) {
            requested = null
            return@LaunchedEffect
        }
        val panel = panels[level]
        withFrameNanos { }
        panel.rows[target.key]?.let { row ->
            panel.scroll.scrollTo(RuntimeCascadeGeometry.scrollToReveal(
                row, panel.scroll.value, panel.viewportHeight,
            ))
        }
        withFrameNanos { }
        panel.requesters[target.key]?.let { requester ->
            try { requester.requestFocus() } catch (_: IllegalStateException) { }
        }
        requested = null
    }
    EditorPopup(
        popupPositionProvider = position,
        onDismissRequest = { dismiss(false) },
        properties = PopupProperties(
            focusable = true, dismissOnBackPress = false, clippingEnabled = false,
            usePlatformInsets = false,
        ),
    ) {
        NavigationBackHandler(
            rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = ::back,
        )
        val hitRegions = remember { CascadeHitRegions() }
        SubcomposeLayout(
            Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                refreshInput()
                if (!state.isOpen) { revision++; return@onPreviewKeyEvent true }
                val level = focused?.parents?.size?.coerceAtMost(state.path.size) ?: 0
                val entries = state.entries(level)
                val index = entries.indexOfFirst { it.key == focused?.key }.coerceAtLeast(0)
                when (event.key) {
                    Key.Escape, Key.DirectionLeft -> back()
                    Key.DirectionDown, Key.DirectionUp, Key.Tab, Key.MoveHome, Key.MoveEnd -> {
                        val next = RuntimeCascadeGeometry.focusIndex(
                            entries.size, index, event.key, event.isShiftPressed,
                        )
                        if (next != null) requested = CascadeFocus(state.path.take(level), entries[next].key)
                    }
                    Key.DirectionRight, Key.Enter, Key.Spacebar -> {
                        val entry = entries.firstOrNull { it.key == focused?.key }
                        if (entry != null && (event.key != Key.DirectionRight || entry is RuntimeMenuEntry.Branch)) {
                            state.target(level, entry.key)?.let(::activate)
                        }
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }.pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press &&
                            event.changes.any { !hitRegions.contains(it.position.x, it.position.y) }) {
                            event.changes.forEach { it.consume() }
                            dismiss(false)
                        }
                    }
                }
            },
        ) { constraints ->
            val window = position.window.takeIf { it != IntSize.Zero }
                ?: IntSize(constraints.maxWidth, constraints.maxHeight)
            val anchor = anchorBoundsInWindow ?: position.anchor
            val surfaces = columns.mapIndexed { level, entries ->
                val minimumWidth = 120.dp.roundToPx()
                val maximumWidth = 220.dp.roundToPx()
                val horizontalPadding = 12.dp.roundToPx()
                val selectionWidth = 16.dp.roundToPx()
                val branchWidth = 14.dp.roundToPx()
                val textLimit = (maximumWidth - horizontalPadding - selectionWidth).coerceAtLeast(1)
                val preferredWidth = entries.maxOfOrNull { entry ->
                    val textWidth = textMeasurer.measure(
                        AnnotatedString(entry.label), style = rowTextStyle, softWrap = false, maxLines = 1,
                        constraints = Constraints(maxWidth = textLimit),
                    ).size.width
                    horizontalPadding + selectionWidth + textWidth +
                        if (entry is RuntimeMenuEntry.Branch) branchWidth else 0
                } ?: minimumWidth
                val columnWidth = preferredWidth.coerceIn(minimumWidth, maximumWidth)
                subcompose(state.path.take(level)) {
                    CascadeSurface(
                        entries, panels[level], field, state.path.getOrNull(level),
                        onFocus = { focused = CascadeFocus(state.path.take(level), it) },
                        target = { state.target(level, it) },
                        generation = state.eventGeneration,
                        onActivate = ::activate,
                    )
                }.single().measure(Constraints(
                    minWidth = columnWidth, maxWidth = columnWidth,
                    maxHeight = minOf(480.dp.roundToPx(), window.height).coerceAtLeast(1),
                ))
            }
            val rectangles = mutableListOf<IntRect>()
            surfaces.forEachIndexed { level, surface ->
                panels[level].viewportHeight = surface.height
                val size = IntSize(surface.width, surface.height)
                val offset = if (level == 0) RuntimeCascadeGeometry.root(anchor, size, window) else {
                    val parent = rectangles[level - 1]
                    val panel = panels[level - 1]
                    val row = panel.rows[state.path[level - 1]] ?: IntRect.Zero
                    RuntimeCascadeGeometry.child(
                        parent, row.translate(IntOffset(parent.left, parent.top - panel.scroll.value)),
                        size, window,
                    )
                }
                rectangles += IntRect(offset, size)
            }
            hitRegions.rectangles = rectangles
            // The transparent window-sized layer owns blank/outside presses. Only surfaces
            // are group hits; no synthetic forwarding to controls underneath the overlay.
            layout(window.width, window.height) {
                surfaces.forEachIndexed { index, surface ->
                    surface.place(rectangles[index].topLeft, zIndex = index.toFloat())
                }
            }
        }
    }
}

@Composable
private fun CascadeSurface(
    entries: List<RuntimeMenuEntry>,
    panel: CascadePanel,
    field: String,
    expandedKey: Any?,
    onFocus: (Any) -> Unit,
    target: (Any) -> RuntimeCascadeMenuState.Target?,
    generation: Long,
    onActivate: (RuntimeCascadeMenuState.Target) -> Unit,
) {
    panel.requesters.keys.retainAll(entries.map { it.key }.toSet())
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.semantics { contentDescription = "$field menu" },
    ) {
        Layout(
            modifier = Modifier.verticalScroll(panel.scroll),
            content = {
                entries.forEach { entry ->
                    key(entry.key) {
                        val requester = panel.requesters.getOrPut(entry.key) { FocusRequester() }
                        val eventTarget = target(entry.key)?.takeIf { it.generation == generation }
                        var itemFocused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 32.dp)
                                .focusRequester(requester).onFocusChanged {
                                itemFocused = it.isFocused
                                if (it.isFocused) onFocus(entry.key)
                            }.border(
                                1.dp, if (itemFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                                MaterialTheme.shapes.extraSmall,
                            ).selectable(
                                selected = entry.selected,
                                role = Role.Button,
                                onClick = { eventTarget?.let(onActivate) },
                            ).semantics {
                                contentDescription = "$field: ${entry.label}"
                                selected = entry.selected
                            }.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (entry.selected) {
                                Text("✓", modifier = Modifier.width(16.dp))
                            } else {
                                Spacer(Modifier.width(16.dp))
                            }
                            Text(entry.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (entry is RuntimeMenuEntry.Branch) {
                                Text(
                                    if (entry.key == expandedKey) "▸" else "›",
                                    modifier = Modifier.width(14.dp),
                                )
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val rows = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
            var y = 0
            panel.rows = rows.mapIndexed { index, row ->
                val bounds = IntRect(0, y, row.width, y + row.height)
                y += row.height
                entries[index].key to bounds
            }.toMap()
            layout(constraints.maxWidth, y) {
                var top = 0
                rows.forEach { row -> row.place(0, top); top += row.height }
            }
        }
    }
}

private data class CascadeInput(
    val owner: Any, val roots: List<RuntimeMenuEntry>, val enabled: Boolean, val expanded: Boolean,
)
private data class CascadeFocus(val parents: List<Any>, val key: Any)
private class CascadeOpenTracker { var expanded = false }
private class CascadePanel {
    val scroll = ScrollState(0)
    val requesters = mutableMapOf<Any, FocusRequester>()
    var rows: Map<Any, IntRect> = emptyMap()
    var viewportHeight = 0
}
private class CascadeHitRegions {
    var rectangles: List<IntRect> = emptyList()
    fun contains(x: Float, y: Float) = rectangles.any {
        x >= it.left && x < it.right && y >= it.top && y < it.bottom
    }
}
private class CascadeWindowPosition : PopupPositionProvider {
    var anchor by mutableStateOf(IntRect.Zero)
    var window by mutableStateOf(IntSize.Zero)
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        anchor = anchorBounds
        window = windowSize
        return IntOffset.Zero
    }
}

internal class RuntimeCascadeMenuState {
    private var owner: Any? = null
    private var roots: List<RuntimeMenuEntry> = emptyList()
    private var enabled = false
    private var generation = 0L
    val eventGeneration: Long get() = generation
    var isOpen: Boolean = false
        private set
    var path: List<Any> = emptyList()
        private set

    data class Target(val generation: Long, val path: List<Any>, val key: Any)

    fun update(owner: Any, roots: List<RuntimeMenuEntry>, enabled: Boolean) {
        if (this.owner !== owner) {
            close()
            this.owner = owner
        }
        this.roots = roots
        this.enabled = enabled
        if (!enabled || roots.isEmpty()) {
            if (isOpen) close()
            return
        }
        val validPath = validPath(path)
        if (validPath != path) {
            path = validPath
            generation++
        }
    }

    fun open() {
        if (!enabled || roots.isEmpty()) return
        generation++
        path = emptyList()
        isOpen = true
    }

    fun close() {
        generation++
        isOpen = false
        path = emptyList()
    }

    fun entries(level: Int): List<RuntimeMenuEntry> {
        if (!isOpen || level !in 0..path.size) return emptyList()
        var entries = roots
        for (key in path.take(level)) {
            val branch = entries.filterIsInstance<RuntimeMenuEntry.Branch>().firstOrNull { it.key == key }
                ?: return emptyList()
            entries = branch.children()
        }
        return entries
    }

    fun target(level: Int, key: Any): Target? {
        if (entries(level).none { it.key == key }) return null
        return Target(generation, path.take(level), key)
    }

    fun resolve(target: Target): RuntimeMenuEntry? {
        if (!isOpen || !enabled || target.generation != generation ||
            target.path.size > path.size || target.path != path.take(target.path.size)) return null
        return entries(target.path.size).firstOrNull { it.key == target.key }
    }

    fun accepts(target: Target): Boolean = resolve(target) != null

    fun activate(target: Target) {
        when (val entry = resolve(target) ?: return) {
            is RuntimeMenuEntry.Branch -> {
                path = target.path + entry.key
                generation++
            }
            is RuntimeMenuEntry.Command -> {
                close()
                entry.onSelect()
            }
        }
    }

    fun collapseTo(level: Int) {
        if (!isOpen || level !in 0..path.size) return
        path = path.take(level)
        generation++
    }

    fun back(): Boolean {
        if (!isOpen) return false
        if (path.isEmpty()) close() else collapseTo(path.size - 1)
        return true
    }

    private fun validPath(candidate: List<Any>): List<Any> {
        val result = mutableListOf<Any>()
        var entries = roots
        for (key in candidate) {
            val branch = entries.filterIsInstance<RuntimeMenuEntry.Branch>().firstOrNull { it.key == key } ?: break
            result += key
            entries = branch.children()
        }
        return result
    }
}
