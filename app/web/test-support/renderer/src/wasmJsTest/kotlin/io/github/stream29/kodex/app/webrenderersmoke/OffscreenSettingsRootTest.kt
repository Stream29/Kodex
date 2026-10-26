package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
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
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRootRenderer
import io.github.stream29.kodex.app.usagereset.createUsageResetViewModel
import io.github.stream29.kodex.app.usagereset.contract.UsageResetDependencies
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.rpc.models.NotificationHookType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.*

val offscreenSettingsRootTest by testSuite {
    test("local browser disables unavailable capabilities without replacing shared Settings") { settingsRootScene { fixture ->
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
                SettingsHostCapabilities(directoryBrowsing = false, contextPathEditing = false, providerLogin = false),
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

    test("leaving pages clears exact child slots while browser shell hooks stay unavailable") { settingsRootScene { fixture ->
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
            scene.settle { scene.hasText("Frontend shell hooks are unavailable in this host.") }
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
    val frontendHookPorts = HookPorts(setOf(NotificationHookType.StopAssistantMessage))
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
