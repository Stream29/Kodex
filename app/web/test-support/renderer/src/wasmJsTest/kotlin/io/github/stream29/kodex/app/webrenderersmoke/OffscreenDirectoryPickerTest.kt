package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.createDirectoryPickerViewModel
import io.github.stream29.kodex.app.pathpicker.contract.*
import io.github.stream29.kodex.app.workingdirectory.createWorkingDirectoryViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.compose.rememberComposeDirectoryPickerRenderer
import io.github.stream29.kodex.cli.pathpicker.*
import io.github.stream29.kodex.cli.workingdirectory.WorkingDirectoryContent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.io.files.Path
import kotlin.test.*

val offscreenDirectoryPickerTest by testSuite {
    test("real picker confirms while loading and lazily navigates and filters a large directory without I/O") { offscreenTest {
        val browser = DirectoryBrowserFixture()
        browser.gate = CompletableDeferred()
        val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
        val picker = realPicker(browser, owner)
        val visible = MutableStateFlow(true)
        val selected = mutableListOf<Path>()
        var dismissals = 0
        val scene = OffscreenScene.create {
            if (visible.collectAsState().value) DirectoryPickerContent(
                picker, rememberComposeDirectoryPickerRenderer(),
                onDismissRequest = { dismissals++; visible.value = false }, onDirectorySelected = { selected += it },
                closeOnDispose = false,
            ) else UiText("closed")
        }
        try {
            scene.settle { scene.hasText("Loading directories…") }
            assertFalse(scene.hasText(".."))
            scene.click("Select")
            scene.settle { selected.size == 1 }
            assertEquals(listOf(Path("/fixture")), selected)
            browser.gate?.complete(Unit)
            scene.settle { scene.hasText("row0000/") }
            assertFalse(scene.hasText("row0999/"))
            assertTrue(scene.directoryRowCount() in 1..12)
            val scroll = scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action }.single()
            assertTrue(scroll(500))
            scene.settle { scene.hasText("row0499/") }
            assertFalse(scene.hasText("row0000/"))
            assertTrue(scene.directoryRowCount() in 1..12)
            scene.click("row0499/")
            scene.settle { scene.hasText("No child directories") && scene.hasText("/fixture/row0499") }
            assertEquals(1, selected.size)
            scene.click("..")
            scene.settle { scene.hasText("row0000/") }
            scene.editDirectoryFilter("row0999")
            scene.settle { scene.hasText("row0999/") && !scene.hasText("row0000/") }
            scene.escape()
            scene.settle { picker.state.value.filterQuery.isEmpty() && scene.hasText("row0000/") }
            assertEquals(0, dismissals)
            scene.escape()
            scene.settle { scene.hasText("closed") }
            assertEquals(1, dismissals)
            assertEquals(0, picker.closeCalls)
            assertTrue(owner.coroutineContext.job.isActive)
            scene.frame()
        } finally {
            scene.close()
            if (picker.closeCalls == 0) picker.close()
            owner.coroutineContext.job.cancelAndJoin()
        }
    } }
    val directory = Path("/fixture")
    val branches = listOf(
        DirectoryPickerState(loadState = fixtureReady(directory)) to "No child directories",
        DirectoryPickerState("missing", fixtureReady(directory, listOf("alpha"))) to "No matching directories",
        DirectoryPickerState(loadState = DirectoryPickerLoadState.Failed(1, directory, DirectoryPickerFailure.HomeDirectoryUnavailable)) to
            "Cannot resolve ~ because the user home directory was not found.",
        DirectoryPickerState(loadState = DirectoryPickerLoadState.Failed(1, directory, DirectoryPickerFailure.NotDirectory(directory))) to
            "Not a directory: /fixture",
        DirectoryPickerState(loadState = DirectoryPickerLoadState.Failed(1, directory, DirectoryPickerFailure.FileSystem("Safe failure"))) to "Safe failure",
    )
    for ((index, branch) in branches.withIndex()) {
        test("shared picker branch $index remains dismissible and closes only an owned child") { offscreenTest {
            val picker = ControlledPicker(branch.first)
            var dismissals = 0
            val scene = OffscreenScene.create {
                DirectoryPickerContent(picker, rememberComposeDirectoryPickerRenderer(), { dismissals++ }, {})
            }
            try {
                scene.settle { scene.hasText(branch.second) }
                assertTrue(scene.hasText("/fixture"))
                assertTrue(scene.hasText("Select"))
                scene.click("Cancel")
                assertEquals(1, dismissals)
                scene.frame()
            } finally { scene.close() }
            assertEquals(1, picker.closeCalls)
        } }
    }
    test("outside click dismisses rather than clearing a nonempty filter and releases the back handler") { offscreenTest {
        val picker = ControlledPicker(DirectoryPickerState(
            "alpha", fixtureReady(directory, listOf("alpha")),
        ))
        val visible = MutableStateFlow(true)
        var dismissals = 0
        val scene = OffscreenScene.create {
            if (visible.collectAsState().value) DirectoryPickerContent(
                picker, rememberComposeDirectoryPickerRenderer(), { dismissals++; visible.value = false }, {},
            ) else UiText("closed")
        }
        try {
            scene.settle { scene.hasText("alpha/") }
            scene.scene.sendPointerEvent(PointerEventType.Press, Offset(1f, 1f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.scene.sendPointerEvent(PointerEventType.Release, Offset(1f, 1f), buttons = PointerButtons(), button = PointerButton.Primary)
            scene.settle { scene.hasText("closed") }
            assertEquals("alpha", picker.state.value.filterQuery)
            assertEquals(1, picker.closeCalls)
            scene.escape()
            scene.settle { true }
            assertEquals(1, dismissals)
        } finally { scene.close() }
    } }
    test("captured typeahead and Escape callbacks use the latest filter without waiting for recomposition") { offscreenTest {
        val picker = ControlledPicker(DirectoryPickerState(loadState = fixtureReady(directory)))
        var letter: ((String) -> Unit)? = null
        var backspace: (() -> Boolean)? = null
        var escape: (() -> Unit)? = null
        var dismissals = 0
        val scene = OffscreenScene.create {
            val graphical = rememberComposeDirectoryPickerRenderer()
            val capturing = object : DirectoryPickerRenderer by graphical {
                @Composable override fun Dialog(
                    presentation: DirectoryPickerPresentation, onDismiss: () -> Unit, onEscape: () -> Unit, onConfirm: () -> Unit,
                    onNavigateUp: () -> Unit, onFilter: (String) -> Unit, onFilterLetter: (String) -> Unit, onBackspace: () -> Boolean,
                ) {
                    letter = onFilterLetter; backspace = onBackspace; escape = onEscape
                    graphical.Dialog(presentation, onDismiss, onEscape, onConfirm, onNavigateUp, onFilter, onFilterLetter, onBackspace)
                }
            }
            DirectoryPickerContent(picker, capturing, { dismissals++ }, {})
        }
        try {
            scene.settle { scene.hasText("No child directories") }
            assertNotNull(letter)("a")
            assertNotNull(letter)("b")
            assertTrue(assertNotNull(backspace)())
            assertEquals("a", picker.state.value.filterQuery)
            assertNotNull(escape)()
            assertEquals("", picker.state.value.filterQuery)
            assertNotNull(escape)()
            assertEquals(1, dismissals)
        } finally { scene.close() }
    } }
    for (complete in listOf(false, true)) {
        test("working-directory selection retains exact owner and closes the borrowed picker once complete=$complete") { offscreenTest {
            val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
            val picker = realPicker(DirectoryBrowserFixture(), owner)
            val gate = CompletableDeferred<Unit>()
            val selections = mutableListOf<Path>()
            var cancelled = 0
            val working = createWorkingDirectoryViewModel(picker, WorkingDirectoryDependencies {
                selections += it
                if (!complete) try { gate.await() } catch (failure: CancellationException) { cancelled++; throw failure }
            })
            val visible = MutableStateFlow(true)
            var selectedCallbacks = 0
            val scene = OffscreenScene.create {
                if (visible.collectAsState().value) WorkingDirectoryContent(
                    working, rememberComposeDirectoryPickerRenderer(), { visible.value = false },
                    onSelected = { selectedCallbacks++; visible.value = false },
                ) else UiText("closed")
            }
            try {
                scene.settle { scene.hasText("row0000/") }
                scene.click("Select")
                scene.settle { selections.size == 1 }
                if (!complete) visible.value = false
                scene.settle { scene.hasText("closed") }
                if (!complete) withTimeout(5_000) { while (cancelled == 0) delay(1) }
                assertEquals(listOf(Path("/fixture")), selections)
                assertEquals(if (complete) 1 else 0, selectedCallbacks)
                assertEquals(if (complete) 0 else 1, cancelled)
                assertEquals(1, picker.closeCalls)
                assertFalse(working.isActive)
                assertTrue(owner.coroutineContext.job.isActive)
            } finally {
                scene.close()
                working.close()
                owner.coroutineContext.job.cancelAndJoin()
            }
        } }
    }
}

private fun OffscreenScene.editDirectoryFilter(value: String) {
    val edit = nodes().mapNotNull { it.config.getOrNull(SemanticsActions.SetText)?.action }.single()
    assertTrue(edit(AnnotatedString(value)))
}

private fun OffscreenScene.directoryRowCount(): Int = nodes().flatMap {
    it.config.getOrNull(SemanticsProperties.Text).orEmpty()
}.count { it.text.startsWith("row") && it.text.endsWith("/") }

private fun realPicker(browser: DirectoryBrowserFixture, owner: CoroutineScope): CountingPicker =
    CountingPicker(createDirectoryPickerViewModel(Path("/fixture"), object : DirectoryPickerDependencies {
        override val browser = browser
    }, owner))

private class CountingPicker(private val delegate: DirectoryPickerViewModel) : DirectoryPickerViewModel by delegate {
    var closeCalls = 0
    override fun close() { closeCalls++; delegate.close() }
}

private class DirectoryBrowserFixture : DirectoryPickerBrowser {
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun validate(directory: Path): DirectoryPickerValidationResult = DirectoryPickerValidationResult.Success(directory)
    override suspend fun load(directory: Path): DirectoryPickerLoadResult {
        gate?.await()
        val ready = fixtureReady(directory, if (directory == Path("/fixture")) List(1_000) { "row${it.toString().padStart(4, '0')}" } else emptyList())
        return DirectoryPickerLoadResult.Success(DirectoryPickerListing(
            directory, ready.children, ready.parentDirectory, ready.childNames,
        ))
    }
}

private fun fixtureReady(directory: Path, names: List<String> = emptyList()): DirectoryPickerLoadState.Ready {
    val children = names.associate { Path("$directory/$it") to it }
    val parent = if (directory == Path("/fixture")) Path("/") else Path("/fixture")
    return DirectoryPickerLoadState.Ready(1, directory, directory, children.keys.toList(), parent, children)
}

private class ControlledPicker(initial: DirectoryPickerState) : DirectoryPickerViewModel {
    override val state = MutableStateFlow(initial)
    private val output = Channel<DirectoryPickerEffect>(Channel.BUFFERED)
    override val effects = output.receiveAsFlow()
    var closeCalls = 0
    override fun navigateTo(directory: Path) = Unit
    override fun navigateUp() = Unit
    override fun retry() = Unit
    override fun updateFilter(query: String) { state.value = state.value.copy(filterQuery = query) }
    override fun clearFilter() { updateFilter("") }
    override fun confirm() { output.trySend(DirectoryPickerEffect.DirectorySelected(state.value.currentDirectory)) }
    override fun close() { closeCalls++; output.close() }
}
