package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.accountusage.*
import io.github.stream29.kodex.app.applicationpreferences.createApplicationPreferencesViewModel
import io.github.stream29.kodex.app.authenticationsettings.createAuthenticationSettingsViewModel
import io.github.stream29.kodex.app.contextsourcesettings.*
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.app.mcpsettings.*
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.sessiontitlesettings.createSessionTitleSettingsViewModel
import io.github.stream29.kodex.app.settings.*
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiAction
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.app.ui.UiChoiceOption
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRootRenderer
import io.github.stream29.kodex.app.usagereset.createUsageResetViewModel
import io.github.stream29.kodex.app.usagereset.contract.UsageResetDependencies
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.rpc.models.NotificationHookType
import io.github.stream29.kodex.rpc.models.NotificationHook
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.*

val offscreenSettingsRootTest by testSuite {
    test("nested Settings modal pages its body and Escape restores only its trigger") { offscreenTest {
        var modal by mutableStateOf(false)
        var dismissals = 0
        val scene = OffscreenScene.create(width = 900, height = 600) {
            val root = rememberComposeSettingsRootRenderer()
            root.Frame(SettingsPage.General, listOf(SettingsNavigationEntry(SettingsPage.General, "General")),
                {}, { dismissals++ }, {}, {
                    UiAction("Open nested modal", { modal = true })
                })
            if (modal) root.Modal(SettingsModalFrame.ContextSource, "Nested modal", { modal = false }) {
                UiAction("Safe close", { modal = false }, autoFocus = true)
                repeat(50) { UiText("Long modal line $it") }
            }
        }
        fun scroll() = scene.nodes().single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Settings modal content") == true
        }.config[SemanticsProperties.VerticalScrollAxisRange]
        try {
            scene.settle { scene.hasText("Open nested modal") }
            scene.click("Open nested modal")
            scene.settle { scene.overlayTextFocused("Safe close") }
            assertEquals(0f, scroll().value())
            scene.key(Key.PageDown)
            scene.settle("modal PageDown") { scroll().value() > 0f }
            scene.key(Key.PageUp)
            scene.settle("modal PageUp") { scroll().value() == 0f }
            scene.escape()
            scene.settle { !scene.hasText("Safe close") && scene.overlayTextFocused("Open nested modal") }
            assertEquals(0, dismissals)
            scene.escape()
            scene.settle { dismissals == 1 }
        } finally { scene.close() }
    } }

    test("choice removal disable and field replacement reject retained option callbacks") { offscreenTest {
        val options = mutableStateOf(listOf(UiChoiceOption("one", "One"), UiChoiceOption("two", "Two")))
        val enabled = mutableStateOf(true)
        val field = mutableStateOf("original")
        val commands = mutableListOf<String>()
        val scene = OffscreenScene.create {
            rememberComposeSettingsRootRenderer().fields.preferences.Choice(
                field.value, "Choice", options.value, "one", commands::add, enabled.value, null)
        }
        fun retainedTwo() = assertNotNull(scene.nodes().first {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choice: Two") == true
        }.config.getOrNull(SemanticsActions.OnClick)?.action)
        try {
            scene.settle { scene.hasDescription("Choice: One") }
            scene.clickDescription("Choice: One")
            scene.settle { scene.hasDescription("Choice: Two") }
            val removed = retainedTwo()
            options.value = options.value.take(1)
            scene.settle { !scene.hasDescription("Choice: Two") }
            removed()
            assertTrue(commands.isEmpty())
            scene.escape()
            scene.settle { scene.nodes().none { it.config.getOrNull(SemanticsProperties.Selected) != null } }
            options.value = listOf(UiChoiceOption("one", "One"), UiChoiceOption("two", "Two"))
            scene.settle { true }
            scene.clickDescription("Choice: One")
            scene.settle { scene.hasDescription("Choice: Two") }
            val disabled = retainedTwo()
            enabled.value = false
            scene.settle { !scene.hasDescription("Choice: Two") }
            disabled()
            assertTrue(commands.isEmpty())
            enabled.value = true
            scene.settle { true }
            scene.clickDescription("Choice: One")
            scene.settle { scene.hasDescription("Choice: Two") }
            val replaced = retainedTwo()
            field.value = "replacement"
            scene.settle { !scene.hasDescription("Choice: Two") }
            replaced()
            assertTrue(commands.isEmpty())
            scene.key(Key.Tab)
            scene.key(Key.Enter)
            scene.settle { scene.hasDescription("Choice: Two") }
        } finally { scene.close() }
    } }

    test("Settings navigation uses Tab and spatial traversal across all seven pages") { settingsRootScene { fixture ->
        val scene = OffscreenScene.create(width = 1024, height = 768) {
            SettingsContent(fixture.root, rememberComposeSettingsRootRenderer(), { fixture.dismissals++ }, {})
        }
        fun navigationFocused(page: SettingsPage) = scene.nodes().any {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Settings page ${page.name}") == true &&
                it.config.getOrNull(SemanticsProperties.Focused) == true
        }
        try {
            scene.settle { navigationFocused(SettingsPage.General) }
            for (page in SettingsPage.entries.drop(1)) {
                scene.key(Key.DirectionDown)
                scene.settle { navigationFocused(page) }
                scene.key(Key.Enter)
                scene.settle { scene.hasDescription("Settings content ${page.name}") }
                scene.key(Key.DirectionRight)
                scene.settle { !navigationFocused(page) }
                var lefts = 0
                while (SettingsPage.entries.none(::navigationFocused) && lefts++ < 12) {
                    scene.key(Key.DirectionLeft)
                    scene.settle { true }
                }
                assertTrue(SettingsPage.entries.any(::navigationFocused),
                    "Left returns to Settings navigation from $page; focused=" + scene.nodes().filter {
                        it.config.getOrNull(SemanticsProperties.Focused) == true
                    }.map { it.config.toString() })
                var tabs = 0
                while (!navigationFocused(page) && tabs++ < SettingsPage.entries.size) {
                    val current = SettingsPage.entries.indexOfFirst(::navigationFocused)
                    scene.key(Key.Tab, shift = current > SettingsPage.entries.indexOf(page))
                    scene.settle { true }
                }
                assertTrue(navigationFocused(page), "Tab returns to the selected $page navigation entry")
            }
            scene.key(Key.DirectionUp)
            scene.settle { navigationFocused(SettingsPage.CurrentSession) }
            scene.key(Key.Tab, shift = true)
            scene.settle { navigationFocused(SettingsPage.Hooks) }
            assertEquals(0, fixture.dismissals)
        } finally { scene.close() }
    } }

    for (selectedKey in listOf("two", "missing")) {
        test("nested choice initially focuses selected or first and restores trigger selected=$selectedKey") { offscreenTest {
            var dismissals = 0
            val commands = mutableListOf<String>()
            val scene = OffscreenScene.create(width = 900, height = 600) {
                val root = rememberComposeSettingsRootRenderer()
                root.Frame(SettingsPage.General,
                    listOf(SettingsNavigationEntry(SettingsPage.General, "General")), {}, { dismissals++ }, {}, {
                        root.fields.preferences.Choice("choice", "Choice",
                            listOf(UiChoiceOption("one", "One"), UiChoiceOption("two", "Two")),
                            selectedKey, commands::add, true, null)
                    })
            }
            fun optionFocused(label: String) = scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choice: $label") == true &&
                    it.config.getOrNull(SemanticsProperties.Selected) != null &&
                    it.config.getOrNull(SemanticsProperties.Focused) == true
            }
            fun triggerFocused() = scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choice: ${if (selectedKey == "two") "Two" else "missing"}") == true &&
                    it.config.getOrNull(SemanticsProperties.Selected) == null &&
                    it.config.getOrNull(SemanticsProperties.Focused) == true
            }
            try {
                scene.settle { scene.hasText("Settings") }
                scene.clickDescription("Choice: ${if (selectedKey == "two") "Two" else "missing"}")
                scene.settle("initial choice focus") { optionFocused(if (selectedKey == "two") "Two" else "One") }
                scene.key(Key.Tab, shift = selectedKey == "two")
                scene.settle { optionFocused(if (selectedKey == "two") "One" else "Two") }
                scene.escape()
                scene.settle("dropdown dismissal restores trigger") { !scene.hasText("One") && triggerFocused() }
                assertEquals(0, dismissals)
                assertTrue(commands.isEmpty())
                scene.key(Key.Enter)
                scene.settle { optionFocused(if (selectedKey == "two") "Two" else "One") }
                scene.key(Key.DirectionDown)
                scene.key(Key.DirectionUp)
                scene.settle { optionFocused(if (selectedKey == "two") "Two" else "One") }
                scene.key(Key.Spacebar)
                scene.settle("selection restores trigger") { commands.size == 1 && triggerFocused() }
                assertEquals(listOf(if (selectedKey == "two") "two" else "one"), commands)
                assertEquals(0, dismissals)
                scene.escape()
                scene.settle { dismissals == 1 }
            } finally { scene.close() }
        } }
    }
    for (dark in listOf(false, true)) {
        for (fontScale in listOf(1f, 1.5f)) {
            test("compact choice focus is visible and commits once dark=$dark font=$fontScale") { offscreenTest {
                val commands = mutableListOf<String>()
                val scene = OffscreenScene.create(width = 480, height = 240) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                            rememberComposeSettingsRootRenderer().fields.preferences.Choice(
                                "density", "Density", listOf(UiChoiceOption("one", "One"), UiChoiceOption("two", "Two")),
                                "one", { commands += it }, true, null,
                            )
                        }
                    }
                }
                try {
                    scene.settle { scene.hasDescription("Density: One") }
                    scene.clickDescription("Density: One")
                    scene.settle { scene.hasDescription("Density: Two") }
                    fun option(name: String) = scene.nodes().first {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Density: $name") == true &&
                            it.config.getOrNull(SemanticsProperties.Selected) != null
                    }
                    assertTrue(assertNotNull(option("One").config.getOrNull(SemanticsActions.RequestFocus)?.action)())
                    scene.settle { option("One").config.getOrNull(SemanticsProperties.Focused) == true }
                    repeat(30) { scene.advance() }
                    val first = scene.frame()
                    assertTrue(first.contentEquals(scene.frame()), "The first focused raster must be stable.")
                    assertTrue(assertNotNull(option("Two").config.getOrNull(SemanticsActions.RequestFocus)?.action)())
                    scene.settle { option("Two").config.getOrNull(SemanticsProperties.Focused) == true }
                    repeat(30) { scene.advance() }
                    val second = scene.frame()
                    assertTrue(second.contentEquals(scene.frame()), "The focused raster must be stable.")
                    assertFalse(first.contentEquals(second), "Moving focus must change the visible menu raster.")
                    assertTrue(option("Two").boundsInWindow.height >= 32f)
                    assertTrue(commands.isEmpty())
                    scene.key(Key.Enter)
                    scene.settle { commands.size == 1 }
                    assertEquals(listOf("two"), commands)
                } finally { scene.close() }
            } }
        }
    }
    test("Settings choice menu rows are compact and commit only the exact selected key") { offscreenTest {
        val selected = mutableStateOf("one")
        val commands = mutableListOf<String>()
        val scene = OffscreenScene.create(width = 480, height = 240) {
            rememberComposeSettingsRootRenderer().fields.preferences.Choice(
                "density", "Density", listOf(UiChoiceOption("one", "One"), UiChoiceOption("two", "Two")),
                selected.value, { commands += it }, true, null,
            )
        }
        try {
            scene.settle { scene.hasDescription("Density: One") }
            scene.clickDescription("Density: One")
            scene.settle { scene.hasDescription("Density: Two") }
            val option = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Density: Two") == true
            }
            assertTrue(option.boundsInWindow.height in 32f..40f, "Choice option must use compact row geometry.")
            assertEquals(false, option.config.getOrNull(SemanticsProperties.Selected))
            assertTrue(commands.isEmpty())
            val current = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Density: One") == true &&
                    it.config.getOrNull(SemanticsProperties.Selected) == true
            }
            assertTrue(assertNotNull(current.config.getOrNull(SemanticsActions.RequestFocus)?.action)())
            scene.key(Key.DirectionDown)
            scene.settle { scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Density: Two") == true &&
                    it.config.getOrNull(SemanticsProperties.Focused) == true
            } }
            assertTrue(commands.isEmpty())
            scene.key(Key.Enter)
            scene.settle { commands.size == 1 && !scene.hasDescription("Density: Two") }
            assertEquals(listOf("two"), commands)
            assertEquals("one", selected.value)
        } finally { scene.close() }
    } }
    test("settings sections use compact insets and retain controlled Toggle behavior") { offscreenTest {
        val changes = mutableListOf<Boolean>()
        val scene = OffscreenScene.create(width = 600) {
            val fields = rememberComposeSettingsRootRenderer().fields.preferences
            Box(Modifier.semantics { contentDescription = "Compact section frame" }) {
                fields.Section("Compact section") { UiText("Field"); UiText("Value") }
            }
            Surface(
                Modifier.fillMaxWidth().semantics { contentDescription = "Original section frame" },
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    UiText("Section", UiTextRole.Title)
                    UiText("Field"); UiText("Value")
                }
            }
            fields.Toggle("Controlled target", false, changes::add, true, "Complete supporting text")
        }
        try {
            scene.settle { scene.hasText("Controlled target") }
            fun frame(name: String) = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(name) == true
            }.boundsInRoot
            assertTrue(frame("Compact section frame").height < frame("Original section frame").height)
            val inset = scene.text("Compact section").boundsInRoot.left - frame("Compact section frame").left
            assertTrue(inset in 7f..9f, "Section should use the compact 8dp horizontal inset.")
            val toggle = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            assertTrue(toggle.boundsInRoot.height in 32f..40f, "Toggle should retain a compact reachable target.")
            scene.click("Controlled target")
            scene.click("Controlled target")
            assertEquals(listOf(true, true), changes, "The renderer never invents an optimistic checked value.")
            assertTrue(scene.hasText("Complete supporting text"))
            scene.frame()
        } finally { scene.close() }
    } }

    test("settings Toggle ignores a retained callback after it becomes disabled") { offscreenTest {
        val enabled = mutableStateOf(true)
        val changes = mutableListOf<Boolean>()
        val scene = OffscreenScene.create(width = 480, height = 120) {
            rememberComposeSettingsRootRenderer().fields.preferences.Toggle(
                "Guarded toggle", false, changes::add, enabled.value, "Supporting text",
            )
        }
        fun toggle() = scene.nodes().single {
            it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                it.config.getOrNull(SemanticsActions.OnClick) != null
        }
        try {
            scene.settle { scene.hasText("Guarded toggle") }
            val retained = assertNotNull(toggle().config.getOrNull(SemanticsActions.OnClick)?.action)
            scene.click("Guarded toggle")
            assertEquals(listOf(true), changes)
            enabled.value = false
            scene.settle { SemanticsProperties.Disabled in toggle().config }
            retained()
            assertEquals(listOf(true), changes)
        } finally { scene.close() }
    } }

    for ((width, height) in listOf(1024 to 768, 360 to 640, 320 to 240)) {
        for (fontScale in listOf(1f, 1.5f)) {
            test("all Settings pages keep selected navigation errors and Close reachable ${width}x$height font=$fontScale") { settingsRootScene { fixture ->
                fixture.global.operationFailure.value = true
                val scene = OffscreenScene.create(width = width, height = height) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        SettingsContent(
                            fixture.root, rememberComposeSettingsRootRenderer(), { fixture.dismissals++ }, {},
                            SettingsHostCapabilities(
                                directoryBrowsing = false, contextPathEditing = false,
                                providerLogin = false, frontendErrorHooks = false,
                            ),
                        )
                    }
                }
                try {
                    scene.settle { scene.hasText("Input") }
                    for (page in SettingsPage.entries) {
                        fixture.root.selectPage(page)
                        scene.settle {
                            scene.hasDescription("Settings content ${page.name}") &&
                                scene.nodes().any {
                                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Settings page ${page.name}") == true &&
                                        it.config.getOrNull(SemanticsProperties.Selected) == true && it.boundsInWindow.width > 0
                                }
                        }
                        val selected = scene.nodes().single {
                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Settings page ${page.name}") == true
                        }
                        assertEquals(Role.Tab, selected.config.getOrNull(SemanticsProperties.Role))
                        assertTrue(selected.boundsInWindow.left >= 0 && selected.boundsInWindow.right <= width)
                        val close = scene.text("Close").boundsInWindow
                        assertTrue(close.top >= 0 && close.bottom <= height, "$page Close at $close")
                        assertEquals(1, scene.textCount(SettingsOperationFailureMessage))
                        val body = scene.nodes().single {
                            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Settings content ${page.name}") == true
                        }
                        assertTrue(body.boundsInWindow.height > 0, "$page has a finite content viewport")
                        val scroll = assertNotNull(body.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
                        assertTrue(assertNotNull(selected.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
                        if (scroll.maxValue() > 0) {
                            scene.key(Key.PageDown)
                            scene.settle { scroll.value() > 0 }
                            scene.key(Key.PageUp)
                            scene.settle { scroll.value() == 0f }
                        }
                    }
                    scene.click("Close")
                    assertEquals(1, fixture.dismissals)
                    assertEquals(0, fixture.global.closes + fixture.source.closes)
                    scene.frame()
                } finally { scene.close() }
            } }
        }
    }

    test("explicit unavailable readiness disables capabilities without replacing shared Settings") { settingsRootScene { fixture ->
        var logins = 0
        val scene = OffscreenScene.create {
            val graphical = rememberComposeSettingsRootRenderer()
            val renderer = object : SettingsRootRenderer by graphical {
                @Composable override fun Rename(viewModel: SessionRenameViewModel, onDismiss: () -> Unit) {
                    UiText("Available exact Rename slot")
                }
            }
            SettingsContent(
                fixture.root, renderer, {}, { logins++ },
                SettingsHostCapabilities(
                    directoryBrowsing = false, contextPathEditing = false,
                    providerLogin = false, frontendErrorHooks = false,
                ),
            )
        }
        try {
            scene.settle { scene.hasText("Input") }
            scene.selectSettingsPage(SettingsPage.ContextSources)
            scene.settle { scene.hasText("Context source path editing is unavailable in this host.") }
            assertFalse(scene.hasText("Add source"))
            scene.selectSettingsPage(SettingsPage.OpenAi)
            scene.settle { scene.hasText("Sign in again") }
            scene.click("Sign in again")
            scene.frame()
            assertEquals(0, fixture.global.authPorts.logins)
            assertEquals(0, logins)
            assertTrue(scene.hasText("Provider sign-in is unavailable in this host. Sign in using the Native client."))
            scene.selectSettingsPage(SettingsPage.Hooks)
            scene.settle { scene.hasText("Frontend shell hooks are unavailable in this host.") }
            scene.selectSettingsPage(SettingsPage.CurrentSession)
            scene.settle { scene.hasText("Browse") }
            scene.click("Browse")
            scene.frame()
            assertNull(fixture.root.session.directoryPicker.value)
            assertTrue(fixture.browsers.isEmpty())
            scene.click("Rename")
            scene.settle { fixture.root.session.rename.value != null && scene.hasText("Available exact Rename slot") }
            assertNotNull(fixture.root.session.rename.value)
            assertEquals(0, fixture.source.closes)
        } finally { scene.close() }
    } }
    test("all seven pages share navigation and suppress child failure duplicates without closing their owner") { settingsRootScene { fixture ->
        fixture.global.operationFailure.value = true
        fixture.global.preferencesPorts.operationFailure.value = true
        fixture.global.titlePorts.operationFailure.value = true
        fixture.global.authPorts.operationFailure.value = true
        fixture.global.contextPorts.operationFailure.value = true
        fixture.global.hookPorts.operationFailure.value = true
        fixture.global.mcpPorts.operationFailure.value = true
        var capturedNavigation = emptyList<SettingsNavigationEntry>()
        val scene = OffscreenScene.create {
            val graphical = rememberComposeSettingsRootRenderer()
            val renderer = object : SettingsRootRenderer by graphical {
                @Composable
                override fun Frame(
                    selectedPage: SettingsPage, navigation: List<SettingsNavigationEntry>,
                    onSelect: (SettingsPage) -> Unit, onDismiss: () -> Unit,
                    banner: @Composable () -> Unit, content: @Composable () -> Unit,
                ) {
                    capturedNavigation = navigation
                    graphical.Frame(selectedPage, navigation, onSelect, onDismiss, banner, content)
                }
            }
            SettingsContent(fixture.root, renderer, { fixture.dismissals++ }, {})
        }
        try {
            scene.settle { scene.hasText("Input") }
            assertEquals(SettingsPage.entries, capturedNavigation.map { it.page })
            val markers = linkedMapOf(
                SettingsPage.General to "Input", SettingsPage.ContextSources to "Built-in sources",
                SettingsPage.OpenAi to "OpenAI account", SettingsPage.Mcp to "MCP servers",
                SettingsPage.Hooks to "Backend notifications", SettingsPage.CurrentSession to "Identity",
                SettingsPage.NewSession to "Title generation",
            )
            for ((page, marker) in markers) {
                scene.selectSettingsPage(page)
                scene.settle { scene.hasText(marker) }
                assertEquals(page, fixture.root.selectedPage.value)
                assertEquals(1, scene.textCount(SettingsOperationFailureMessage))
                assertFalse(scene.hasText("A settings operation failed."))
                scene.frame()
            }
            assertEquals(1, fixture.global.usageRefreshes)
            scene.click("Dismiss")
            scene.settle { !scene.hasText(SettingsOperationFailureMessage) }
            assertFalse(fixture.global.operationFailure.value)
            scene.click("Close")
            assertEquals(1, fixture.dismissals)
        } finally { scene.close() }
        assertEquals(0, fixture.global.closes)
        assertEquals(0, fixture.source.closes)
        assertFalse(fixture.global.applicationPreferences.state.value.closed)
    } }

    test("leaving pages clears exact child slots and Ready exposes independent frontend error Hooks") { settingsRootScene { fixture ->
        var renameClicks = 0
        var renameSlot: SessionRenameViewModel? = null
        var dismissRename: (() -> Unit)? = null
        val scene = OffscreenScene.create {
            val graphical = rememberComposeSettingsRootRenderer()
            val sessionFields = object : SettingsRenderer by graphical.fields.session {
                @Composable override fun Action(label: String, onClick: () -> Unit, enabled: Boolean, autoFocus: Boolean) {
                    graphical.fields.session.Action(label, { if (label == "Rename") renameClicks++; onClick() }, enabled, autoFocus)
                }
            }
            val renderer = object : SettingsRootRenderer by graphical {
                override val fields = graphical.fields.copy(session = sessionFields)
                @Composable
                override fun Rename(viewModel: SessionRenameViewModel, onDismiss: () -> Unit) {
                    renameSlot = viewModel
                    dismissRename = onDismiss
                    UiText("Captured rename slot")
                }
            }
            SettingsContent(fixture.root, renderer, {}, {})
        }
        try {
            scene.settle { scene.hasText("Input") }
            scene.selectSettingsPage(SettingsPage.ContextSources)
            scene.settle { scene.hasText("Add source") }
            scene.click("Add source")
            scene.settle { scene.hasText("Add context source") }
            fixture.root.selectPage(SettingsPage.General)
            scene.settle { scene.hasText("Input") && !scene.hasText("Add context source") }
            assertEquals(ContextSourceSettingsDialog.Hidden, fixture.global.contextSourceSettings.state.value.dialog)
            scene.selectSettingsPage(SettingsPage.CurrentSession)
            scene.settle("Current Session page") { scene.hasText("Rename") }
            scene.click("Rename")
            assertEquals(1, renameClicks, "Rename pointer activation at ${scene.text("Rename").boundsInWindow}")
            scene.settle("Rename command opens its exact child") { fixture.root.session.rename.value != null }
            val rename = assertNotNull(fixture.root.session.rename.value)
            scene.settle { scene.hasText("Captured rename slot") && renameSlot === rename.viewModel }
            val oldDismiss = assertNotNull(dismissRename)
            fixture.root.selectPage(SettingsPage.General)
            scene.settle("Leaving Rename clears its child") { scene.hasText("Input") && fixture.root.session.rename.value == null }
            assertFalse(rename.viewModel.isActive)
            scene.selectSettingsPage(SettingsPage.CurrentSession)
            scene.settle("Returned Current Session page") { scene.hasText("Browse") }
            scene.click("Rename")
            scene.settle { scene.hasText("Captured rename slot") && fixture.root.session.rename.value != null }
            val next = assertNotNull(fixture.root.session.rename.value)
            oldDismiss()
            assertSame(next, fixture.root.session.rename.value)
            assertNotNull(dismissRename)()
            scene.settle { fixture.root.session.rename.value == null && !scene.hasText("Captured rename slot") }
            scene.click("Browse")
            scene.settle("Working Directory modal opens") { scene.hasText("Select directory") }
            val browser = fixture.browsers.single()
            fixture.root.selectPage(SettingsPage.General)
            scene.settle { scene.hasText("Input") && !scene.hasText("Select directory") }
            assertEquals(1, browser.closes)
            scene.selectSettingsPage(SettingsPage.Hooks)
            scene.settle { scene.hasText("Frontend errors") && scene.nodes().any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                    text.text.startsWith("frontend-error ")
                } == true
            } }
            assertFalse(scene.hasText("Frontend shell hooks are unavailable in this host."))
            assertEquals(1, scene.nodes().count {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("notify ") } == true
            })
            assertTrue(fixture.global.frontendHookPorts.saved.isEmpty())
            assertEquals(0, fixture.source.closes)
        } finally { scene.close() }
    } }

    test("component dialog hosts remain outside the page branch and carry their exact tokens") { settingsRootScene { fixture ->
        val scene = OffscreenScene.create { SettingsContent(fixture.root, rememberComposeSettingsRootRenderer(), {}, {}) }
        try {
            scene.settle { scene.hasText("Input") }
            fixture.global.hookSettings.add()
            scene.settle { scene.hasText("Add Hook") }
            val hook = assertIs<HookSettingsDialog.Editing>(fixture.global.hookSettings.state.value.dialog)
            scene.click("Cancel")
            scene.settle { !scene.hasText("Add Hook") && scene.hasText("Input") }
            assertEquals(HookSettingsDialog.Hidden, fixture.global.hookSettings.state.value.dialog)
            fixture.global.mcpSettings.add()
            scene.settle { scene.hasText("Add MCP server") }
            assertIs<McpSettingsDialog.Editing>(fixture.global.mcpSettings.state.value.dialog)
            fixture.global.hookSettings.dismiss(hook.token)
            assertIs<McpSettingsDialog.Editing>(fixture.global.mcpSettings.state.value.dialog)
            scene.click("Cancel")
            scene.settle { !scene.hasText("Add MCP server") && scene.hasText("Input") }
            assertEquals(1, fixture.global.hookPorts.releases)
            assertEquals(1, fixture.global.mcpPorts.editReleases)
        } finally { scene.close() }
    } }

    test("Login intent uses the latest callback and the separately retained MCP consumer survives popup removal") { settingsRootScene { fixture ->
        val visible = mutableStateOf(true)
        val latest = mutableStateOf(false)
        var oldCalls = 0
        var currentCalls = 0
        var opens = 0
        var cancelled = 0
        val gate = CompletableDeferred<Unit>()
        val scene = OffscreenScene.create {
            McpSettingsEffects(fixture.global.mcpSettings) {
                opens++
                try { gate.await(); true } catch (failure: CancellationException) { cancelled++; throw failure }
            }
            if (visible.value) SettingsContent(
                fixture.root, rememberComposeSettingsRootRenderer(), {},
                if (latest.value) ({ currentCalls++; visible.value = false }) else ({ oldCalls++ }),
            ) else UiText("Login host")
        }
        try {
            scene.settle { scene.hasText("Input") }
            fixture.global.mcpSettings.details("server")
            scene.settle { fixture.global.mcpSettings.state.value.dialog is McpSettingsDialog.Details }
            val token = (fixture.global.mcpSettings.state.value.dialog as McpSettingsDialog.Details).token
            fixture.global.mcpSettings.login(token)
            scene.settle { opens == 1 }
            latest.value = true
            scene.settle { scene.hasText("Input") }
            fixture.global.intents.send(GlobalSettingsEffect.OpenLogin)
            scene.settle { scene.hasText("Login host") }
            assertEquals(0, oldCalls)
            assertEquals(1, currentCalls)
            assertEquals(0, cancelled)
            assertEquals(0, fixture.global.closes)
            gate.complete(Unit)
            scene.settle { true }
            visible.value = true
            scene.settle { scene.hasText("Input") }
            assertEquals(1, opens)
            assertEquals(0, cancelled)
            assertEquals(0, fixture.global.usageRefreshes)
            assertEquals(0, fixture.global.mcpPorts.logins.single().cancellations)
        } finally { scene.close() }
    } }
}

private fun OffscreenScene.selectSettingsPage(page: SettingsPage) {
    val node = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Settings page ${page.name}") == true }
    assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action)())
}

private fun OffscreenScene.textCount(value: String): Int = nodes().count {
    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
}

internal suspend fun settingsRootScene(block: suspend (RootSettingsFixture) -> Unit) = offscreenTest {
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val fixture = RootSettingsFixture(owner)
    try { block(fixture) } finally { fixture.root.close(); owner.coroutineContext.job.cancelAndJoin() }
}

internal class RootSettingsFixture(owner: CoroutineScope) {
    val global = RootGlobalFixture(owner)
    val source = SessionSettingsSource()
    val browsers = mutableListOf<SettingsDirectoryFixture>()
    val root = createSettingsViewModel(
        SettingsPage.General, global,
        createSessionSettingsViewModel(SessionSettingsDependencies(
            source, DefaultsPorts().models,
            createDirectoryPicker = { SettingsDirectoryFixture(it).also(browsers::add) },
            reportUnhandledError = { failure, _ -> throw AssertionError("Unexpected Session failure", failure) },
        ), owner),
        createNewSessionDefaultsViewModel(DefaultsPorts(), owner),
    )
    var dismissals = 0
}

internal class RootGlobalFixture(owner: CoroutineScope) : GlobalSettingsViewModel {
    val preferencesPorts = PreferencePorts(20 to 30)
    val titlePorts = TitlePorts()
    val authPorts = AuthPorts()
    val contextPorts = ContextPorts()
    val hookPorts = HookPorts(NotificationHookType.entries.toSet())
    val frontendHookPorts = HookPorts(setOf(NotificationHookType.StopUnhandledError)).apply {
        hooks.value = listOf(NotificationHook(
            "frontend-error", setOf(NotificationHookType.StopUnhandledError), "frontend-error-command",
        ))
    }
    val mcpPorts = McpPorts()
    override val applicationPreferences = createApplicationPreferencesViewModel(preferencesPorts, owner)
    override val sessionTitleSettings = createSessionTitleSettingsViewModel(titlePorts, owner)
    override val authenticationSettings = createAuthenticationSettingsViewModel(authPorts, owner)
    override val contextSourceSettings = createContextSourceSettingsViewModel(contextPorts, owner)
    override val hookSettings = createHookSettingsViewModel(hookPorts, owner)
    override val frontendErrorHooks = createHookSettingsViewModel(frontendHookPorts, owner)
    override val mcpSettings = createMcpSettingsViewModel(mcpPorts, owner)
    override val operationFailure = MutableStateFlow(false)
    private val usage = MutableStateFlow<SettingsAccountUsageState>(SettingsAccountUsageState.Unavailable)
    var usageRefreshes = 0
    override val usageReset = createUsageResetViewModel(object : UsageResetDependencies {
        override val usage = this@RootGlobalFixture.usage
        override suspend fun consume(creditId: String): CodexRateLimitResetOutcome = error("No credit in fixture")
        override suspend fun refreshUsage() { usageRefreshes++ }
    }, owner)
    override val accountUsage = createAccountUsageViewModel(object : AccountUsageDependencies {
        override val usage = this@RootGlobalFixture.usage
        override val operationFailure = this@RootGlobalFixture.operationFailure
        override suspend fun refresh() { usageRefreshes++ }
        override fun requestReset() { usageReset.show() }
        override fun reportFailure(failure: Throwable) { operationFailure.value = true }
        override fun dismissFailure() { operationFailure.value = false }
    }, owner)
    val intents = Channel<GlobalSettingsEffect>(Channel.BUFFERED)
    override val effects = intents.receiveAsFlow()
    var closes = 0
    override fun dismissOperationFailure() { operationFailure.value = false }
    override fun close() {
        if (closes != 0) return
        closes++
        contextSourceSettings.close(); sessionTitleSettings.close(); applicationPreferences.close()
        authenticationSettings.close(); accountUsage.close(); usageReset.close()
        mcpSettings.close(); hookSettings.close(); frontendErrorHooks.close(); intents.close()
    }
}
