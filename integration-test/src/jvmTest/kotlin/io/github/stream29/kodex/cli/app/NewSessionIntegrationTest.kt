package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.TestMosaic
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.app.application.contract.ApplicationTabSelection
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.settings.contract.SessionSettingsState
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.utils.rpcexception.AgentRunFailureReported
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem as Fs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Actual Application, child factories, JSON/kRPC, filesystem backend and Mosaic root. */
val newSessionIntegrationTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("empty root plus and zero-length Enter never allocate a Session") {
        withBoundaryRoot(seedHistory = false) {
            val singleton = application.newSession
            runBoundaryMosaic {
                setContentAndSnapshot { SessionTreeCliScreen(application, newLineKey, sidebar) }
                sendKeyEvent(KeyboardEvent(13))
                repeat(3) { clickBoundaryText(boundarySnapshot("[+]"), "[+]") }
                awaitBoundaryCondition { application.navigation.value.selection == ApplicationTabSelection.NewSession }
                assertTrue(application.navigation.value.tabs.isEmpty())
                assertNull(application.navigation.value.selectedSession)
                assertNull(application.submitNewSession(singleton.composer.state.value.revision))
                assertTrue(services.global.getSessionCatalog(true).isEmpty())
                assertSame(singleton, application.newSession)
                assertEquals(ComposerLifecycle.Open, singleton.composer.state.value.lifecycle)
                assertTrue(reports.value.isEmpty())
            }
        }
    }

    test("root tab and plus preserve singleton text cursor cwd and persisted-only count") {
        withBoundaryRoot {
            val tab = application.openSession(historyIndex)
            val singleton = application.newSession
            val cwd = Path(root, "chosen")
            Fs.createDirectories(cwd)
            singleton.updateWorkingDirectory(cwd)
            singleton.settings.first { it.cwd == cwd }
            singleton.composer.update("  exact draft  ", 5)
            val original = singleton.composer.state.value
            application.selectNewSession()
            runBoundaryMosaic {
                setContentAndSnapshot { SessionTreeCliScreen(application, newLineKey, sidebar) }
                boundarySnapshot("exact draft")
                assertRootSelection(newSession = true)
                clickBoundaryText(boundarySnapshot("[Root history]"), "[Root history]")
                awaitBoundaryCondition { application.navigation.value.selectedSession === tab }
                assertRootSelection(newSession = false)
                clickBoundaryText(boundarySnapshot("[+]"), "[+]")
                boundarySnapshot("exact draft")
                assertRootSelection(newSession = true)
                assertSame(singleton, application.newSession)
                assertEquals(original, singleton.composer.state.value)
                assertEquals(cwd, singleton.settings.value.cwd)
                assertEquals(listOf(tab), application.navigation.value.tabs)
                assertEquals(1, services.global.getSessionCatalog(true).size)
            }
        }
    }

    for ((label, text) in listOf("spaces" to "   ", "newlines" to "\n\n", "padded" to " \n original \t\n")) {
        test("root Enter submits $label verbatim selects resets and permits a second Session") {
            withBoundaryRoot(seedHistory = false) {
                val singleton = application.newSession
                val chosen = Path(root, "first-cwd")
                Fs.createDirectories(chosen)
                singleton.updateWorkingDirectory(chosen)
                singleton.settings.first { it.cwd == chosen }
                singleton.composer.update(text, text.length)
                val consumed = singleton.composer.state.value.revision
                runBoundaryMosaic {
                    setContentAndSnapshot { SessionTreeCliScreen(application, newLineKey, sidebar) }
                    boundarySnapshot("[Settings]")
                    sendKeyEvent(KeyboardEvent(13))
                    awaitBoundaryCondition { application.navigation.value.tabs.size == 1 }
                    val first = application.navigation.value.tabs.single()
                    assertSame(first, application.navigation.value.selectedSession)
                    assertEquals(listOf(ContentItem.InputText(text)), firstMessage(first).content)
                    assertEquals(chosen, first.settings.value.cwd)
                    assertEquals("", singleton.composer.state.value.text)
                    assertEquals(0, singleton.composer.state.value.cursorOffset)
                    assertTrue(singleton.composer.state.value.revision > consumed)
                    singleton.settings.first { it.cwd == root }
                    first.awaitMockAnswer()
                    clickBoundaryText(boundarySnapshot("[+]"), "[+]")
                    singleton.composer.update("second input")
                    val next = singleton.composer.state.value
                    assertNull(application.submitNewSession(consumed), "Late callbacks cannot target the selected Agent.")
                    assertEquals(next, singleton.composer.state.value)
                    boundarySnapshot("second input")
                    sendKeyEvent(KeyboardEvent(13))
                    awaitBoundaryCondition { application.navigation.value.tabs.size == 2 }
                    val second = application.navigation.value.tabs.last()
                    assertNotSame(first, second)
                    assertSame(second, application.navigation.value.selectedSession)
                    assertEquals(listOf(ContentItem.InputText("second input")), firstMessage(second).content)
                    assertEquals(listOf(ContentItem.InputText(text)), firstMessage(first).content)
                    assertEquals(2, services.global.getSessionCatalog(true).size)
                    assertSame(singleton, application.newSession)
                }
            }
        }
    }

    test("paused defaults projection does not delay creation with backend four fields and requested cwd") {
        val releaseDefaults = CompletableDeferred<Unit>()
        try {
            withBoundaryRoot(seedHistory = false, observeDefaultsAfter = releaseDefaults) {
                val singleton = application.newSession
                val old = services.global.getSettings()
                val updated = old.copy(newSession = old.newSession.copy(
                    model = OpenAiModelId("backend-current"),
                    reasoningEffort = ReasoningEffort.Max,
                    serviceTier = ServiceTier.Fast,
                    requestUserInputMode = RequestUserInputMode.NoQuestion,
                ))
                assertTrue(services.global.compareAndSetSettings(old, updated))
                assertEquals(old.newSession.model, singleton.settings.value.model)
                val chosen = Path(root, "requested")
                Fs.createDirectories(chosen)
                singleton.updateWorkingDirectory(chosen)
                singleton.composer.update("no projection barrier")
                val created = assertNotNull(withTimeout(5.seconds) {
                    application.submitNewSession(singleton.composer.state.value.revision)
                })
                assertFalse(releaseDefaults.isCompleted)
                val initial = created.settings.value
                assertEquals(updated.newSession.model, initial.model)
                assertEquals(updated.newSession.reasoningEffort, initial.reasoning.effort)
                assertEquals(updated.newSession.serviceTier, initial.serviceTier)
                assertEquals(updated.newSession.requestUserInputMode, initial.requestUserInputMode)
                assertEquals(chosen, initial.cwd)
                assertEquals("Session ${created.sessionIndex}", initial.threadName)
                releaseDefaults.complete(Unit)
                singleton.settings.first { it.model == updated.newSession.model && it.cwd == root }
                val next = services.global.getSettings()
                assertTrue(services.global.compareAndSetSettings(next, next.copy(
                    newSession = next.newSession.copy(model = OpenAiModelId("later-default")),
                )))
                singleton.settings.first { it.model == OpenAiModelId("later-default") }
                assertEquals(initial, created.settings.value, "Shared defaults never rewrite a persisted Session.")
            }
        } finally { releaseDefaults.complete(Unit) }
    }

    test("Settings defaults and singleton edit one backend source without changing an existing Session") {
        withBoundaryRoot {
            val tab = application.openSession(historyIndex)
            val original = tab.settings.value
            val settings = application.openSettingsPopup(null, SettingsPage.NewSession)
            val page = settings.viewModel.newSession
            val singleton = application.newSession
            singleton.updateModelConfiguration(OpenAiModelId("from-singleton"), ReasoningEffort.Max, ServiceTier.Fast)
            singleton.updateRequestUserInputMode(RequestUserInputMode.NoQuestion)
            val projected = page.state.first {
                it.settings.model == OpenAiModelId("from-singleton") &&
                    it.settings.requestUserInputMode == RequestUserInputMode.NoQuestion
            }
            assertEquals(ReasoningEffort.Max, projected.settings.reasoningEffort)
            assertEquals(ServiceTier.Fast, projected.settings.serviceTier)
            page.updateModel(projected.revision, OpenAiModelId("from-settings"))
            val model = page.state.first { it.settings.model == OpenAiModelId("from-settings") }
            page.updateReasoningEffort(model.revision, ReasoningEffort.High)
            val effort = page.state.first { it.settings.reasoningEffort == ReasoningEffort.High }
            page.updateServiceTier(effort.revision, ServiceTier.Ultrafast)
            val tier = page.state.first { it.settings.serviceTier == ServiceTier.Ultrafast }
            page.updateRequestUserInputMode(tier.revision, RequestUserInputMode.AskUser)
            singleton.settings.first {
                it.model == OpenAiModelId("from-settings") && it.reasoning.effort == ReasoningEffort.High &&
                    it.serviceTier == ServiceTier.Ultrafast && it.requestUserInputMode == RequestUserInputMode.AskUser
            }
            val backend = services.global.getSettings().newSession
            assertEquals(OpenAiModelId("from-settings"), backend.model)
            assertEquals(ReasoningEffort.High, backend.reasoningEffort)
            assertEquals(ServiceTier.Ultrafast, backend.serviceTier)
            assertEquals(RequestUserInputMode.AskUser, backend.requestUserInputMode)
            assertEquals(original, tab.settings.value)
        }
    }

    test("in-flight duplicate and late root revision never create twice or send to a later selected Session") {
        val gate = IntegrationGate()
        var creates = 0
        var appends = 0
        try {
            withBoundaryRoot(decorate = { original ->
                object : RpcClient by original {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "createSession") creates++
                        if (call.callableName == "appendUserMessage") appends++
                        val result = original.call<T>(call)
                        if (call.callableName == "createSession" && creates == 1) gate.wait()
                        return result
                    }
                }
            }) {
                val other = application.openSession(historyIndex)
                application.selectNewSession()
                val singleton = application.newSession
                val chosen = Path(root, "captured-cwd")
                Fs.createDirectories(chosen)
                singleton.updateWorkingDirectory(chosen)
                singleton.settings.first { it.cwd == chosen }
                singleton.composer.update("captured first")
                val revision = singleton.composer.state.value.revision
                runBoundaryMosaic {
                    setContentAndSnapshot { SessionTreeCliScreen(application, newLineKey, sidebar) }
                    boundarySnapshot("captured first")
                    sendKeyEvent(KeyboardEvent(13))
                    awaitBoundaryCondition { gate.entered.isCompleted }
                    assertNull(application.submitNewSession(revision))
                    assertEquals("captured first", singleton.composer.state.value.text)
                    assertEquals(chosen, singleton.settings.value.cwd, "Allocation alone must not reset cwd.")
                    assertEquals(1, creates)
                    assertEquals(0, appends)
                    gate.release.complete(Unit)
                    awaitBoundaryCondition { application.navigation.value.tabs.size == 2 }
                    val first = application.navigation.value.tabs.last()
                    assertEquals(chosen, first.settings.value.cwd)
                    singleton.settings.first { it.cwd == root }
                    first.awaitMockAnswer()
                    assertTrue(application.selectTab(other))
                    singleton.composer.update("next draft")
                    val next = singleton.composer.state.value
                    assertNull(application.submitNewSession(revision))
                    assertSame(other, application.navigation.value.selectedSession)
                    assertEquals(next, singleton.composer.state.value)
                    assertEquals(listOf(ContentItem.InputText("root-history-entry")), firstMessage(other).content)
                    assertEquals(1, creates)
                    assertEquals(1, appends)
                    val second = assertNotNull(application.submitNewSession(next.revision))
                    assertSame(second, application.navigation.value.selectedSession)
                    assertEquals(2, creates)
                    assertEquals(2, appends)
                    assertEquals(3, services.global.getSessionCatalog(true).size)
                }
            }
        } finally { gate.release.complete(Unit) }
    }

    for (newerEdits in listOf(false, true)) {
        test("root renderer cancellation preserves accepted owner work and ${if (newerEdits) "newer edits" else "exact reset"}") {
            val gate = IntegrationGate()
            try {
                withBoundaryRoot(seedHistory = false, decorate = { original ->
                    object : RpcClient by original {
                        override suspend fun <T> call(call: RpcCall): T {
                            val result = original.call<T>(call)
                            if (call.callableName == "createSession") gate.wait()
                            return result
                        }
                    }
                }) {
                    val singleton = application.newSession
                    val chosen = Path(root, "chosen")
                    val later = Path(root, "later")
                    Fs.createDirectories(chosen)
                    Fs.createDirectories(later)
                    singleton.updateWorkingDirectory(chosen)
                    singleton.composer.update("accepted owner text")
                    val consumed = singleton.composer.state.value.revision
                    var laterRevision: Long? = null
                    runBoundaryMosaic {
                        setContentAndSnapshot { SessionTreeCliScreen(application, newLineKey, sidebar) }
                        boundarySnapshot("accepted owner text")
                        sendKeyEvent(KeyboardEvent(13))
                        awaitBoundaryCondition { gate.entered.isCompleted }
                        if (newerEdits) {
                            singleton.composer.update("later unsent text", 3)
                            laterRevision = singleton.composer.state.value.revision
                            singleton.updateWorkingDirectory(later)
                            singleton.updateWorkingDirectory(chosen) // ABA is still a newer cwd edit.
                        }
                        cancel() // Cancels the actual root's rememberCoroutineScope waiter.
                    }
                    gate.release.complete(Unit)
                    val nav = withTimeout(5.seconds) { application.navigation.first { it.tabs.size == 1 } }
                    val created = nav.tabs.single()
                    assertSame(created, nav.selectedSession)
                    assertEquals(listOf(ContentItem.InputText("accepted owner text")), firstMessage(created).content)
                    assertEquals(chosen, created.settings.value.cwd)
                    assertEquals(if (newerEdits) "later unsent text" else "", singleton.composer.state.value.text)
                    if (newerEdits) {
                        assertEquals(laterRevision, singleton.composer.state.value.revision)
                        assertEquals(3, singleton.composer.state.value.cursorOffset)
                    }
                    singleton.settings.first { it.cwd == if (newerEdits) chosen else root }
                    assertNull(application.submitNewSession(consumed))
                    assertEquals(1, services.global.getSessionCatalog(true).size)
                    assertTrue(reports.value.isEmpty())
                }
            } finally { gate.release.complete(Unit) }
        }
    }

    for (stage in listOf("createSession", "create-reply", "keepSessionAlive", "appendUserMessage", "append-reply")) {
        test("root $stage failure reports original once without retry transfer compensation or navigation") {
            val failure = IntegrationFailure(stage)
            var creates = 0
            var appends = 0
            var deletes = 0
            withBoundaryRoot(seedHistory = false, decorate = { original ->
                object : RpcClient by original {
                    override suspend fun <T> call(call: RpcCall): T {
                        when (call.callableName) {
                            "createSession" -> creates++
                            "appendUserMessage" -> appends++
                            "deleteSession" -> deletes++
                        }
                        if (call.callableName == stage) throw failure
                        val result = original.call<T>(call)
                        if (stage == "create-reply" && call.callableName == "createSession") throw failure
                        if (stage == "append-reply" && call.callableName == "appendUserMessage") throw failure
                        return result
                    }
                }
            }) {
                val singleton = application.newSession
                val chosen = Path(root, "failure-cwd")
                Fs.createDirectories(chosen)
                singleton.updateWorkingDirectory(chosen)
                singleton.composer.update("never replay")
                val input = singleton.composer.state.value
                val failures = mutableListOf<Throwable>()
                runBoundaryMosaic {
                    setContentAndSnapshot {
                        SessionTreeCliScreen(application, newLineKey, sidebar, onOperationFailure = { failures += it })
                    }
                    boundarySnapshot("never replay")
                    sendKeyEvent(KeyboardEvent(13))
                    awaitBoundaryCondition { failures.isNotEmpty() }
                    // Flush another root command to expose accidental queued/replayed work.
                    application.selectNewSession()
                    assertEquals(1, failures.size)
                    assertSame(failure, failures.single())
                    assertTrue(reports.value.isEmpty(), "A live renderer waiter is the only reporting outlet.")
                    assertEquals(input, singleton.composer.state.value)
                    singleton.settings.first { it.cwd == chosen }
                    assertTrue(application.navigation.value.tabs.isEmpty())
                    assertEquals(ApplicationTabSelection.NewSession, application.navigation.value.selection)
                    assertEquals(1, creates)
                    assertEquals(if (stage.startsWith("append")) 1 else 0, appends)
                    assertEquals(0, deletes)
                    val catalog = services.global.getSessionCatalog(true)
                    assertEquals(if (stage == "createSession") 0 else 1, catalog.size)
                    if (stage == "append-reply") {
                        val index = catalog.single().sessionIndex
                        val message = assertIs<StableUserMessage>(services.index.get(
                            index, services.index.getCacheNonce(index), 1,
                        ))
                        assertEquals(listOf(ContentItem.InputText("never replay")), message.content,
                            "A lost append reply is not evidence that append did not happen.")
                    }
                }
            }
        }
    }

    test("orphaned root submit failure reaches the Application owner handler exactly once") {
        val gate = IntegrationGate()
        val failure = IntegrationFailure("opening after renderer disposal")
        var creates = 0
        var opens = 0
        try {
            withBoundaryRoot(seedHistory = false, decorate = { original ->
                object : RpcClient by original {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "createSession") creates++
                        if (call.callableName == "keepSessionAlive") {
                            opens++
                            gate.wait()
                            throw failure
                        }
                        return original.call(call)
                    }
                }
            }) {
                val singleton = application.newSession
                singleton.composer.update("orphan failure retains draft")
                val input = singleton.composer.state.value
                val failures = mutableListOf<Throwable>()
                runBoundaryMosaic {
                    setContentAndSnapshot {
                        SessionTreeCliScreen(application, newLineKey, sidebar, onOperationFailure = { failures += it })
                    }
                    boundarySnapshot("orphan failure retains draft")
                    sendKeyEvent(KeyboardEvent(13))
                    awaitBoundaryCondition { gate.entered.isCompleted }
                    cancel()
                }
                gate.release.complete(Unit)
                withTimeout(5.seconds) { reports.first { it.isNotEmpty() } }
                application.selectNewSession()
                assertTrue(failures.isEmpty())
                assertEquals(1, reports.value.size)
                assertSame(failure, reports.value.single())
                assertEquals(input, singleton.composer.state.value)
                assertTrue(application.navigation.value.tabs.isEmpty())
                assertEquals(1, creates)
                assertEquals(1, opens)
                assertEquals(1, services.global.getSessionCatalog(true).size)
            }
        } finally { gate.release.complete(Unit) }
    }

    test("asynchronous model failure does not restore consumed draft or remove the successful tab") {
        val running = CompletableDeferred<Unit>()
        val fail = CompletableDeferred<Unit>()
        val resumeFailure = CompletableDeferred<Throwable>()
        var resumes = 0
        try {
            withBoundaryRoot(seedHistory = false, response = {
                running.complete(Unit)
                fail.await()
                error("controlled external model failure")
            }, decorate = { original ->
                object : RpcClient by original {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName != "resume") return original.call(call)
                        resumes++
                        try {
                            return original.call(call)
                        } catch (error: Throwable) {
                            resumeFailure.complete(error)
                            throw error
                        }
                    }
                }
            }) {
                val singleton = application.newSession
                singleton.composer.update("consumed before model completion")
                val created = assertNotNull(application.submitNewSession(singleton.composer.state.value.revision))
                running.await()
                assertEquals("", singleton.composer.state.value.text)
                assertSame(created, application.navigation.value.selectedSession)
                fail.complete(Unit)
                val agent = assertNotNull(created.rootAgent.value)
                assertIs<AgentRunFailureReported>(withTimeout(5.seconds) { resumeFailure.await() })
                agent.running.first { !it }
                assertEquals("", singleton.composer.state.value.text)
                singleton.settings.first { it.cwd == root }
                assertSame(created, application.navigation.value.selectedSession)
                assertEquals(1, services.global.getSessionCatalog(true).size)
                assertEquals(1, resumes)
                assertTrue(reports.value.isEmpty(), "Backend-reported run failure is not reported again by the frontend.")
            }
        } finally { fail.complete(Unit) }
    }

    test("root no-target Settings and Login returnTo remain unavailable through tab selection and close") {
        withBoundaryRoot {
            val singleton = application.newSession
            runBoundaryMosaic {
                setContentAndSnapshot { SessionTreeCliScreen(application, newLineKey, sidebar) }
                clickBoundaryText(boundarySnapshot("[Settings]"), "[Settings]")
                awaitBoundaryCondition { application.popup.value is ApplicationPopupState.Settings }
                val settings = assertIs<ApplicationPopupState.Settings>(application.popup.value)
                assertNull(settings.target)
                assertEquals(SettingsPage.NewSession, settings.viewModel.selectedPage.value)
                settings.viewModel.selectPage(SettingsPage.CurrentSession)
                boundarySnapshot("No selected session")
                assertEquals(SessionSettingsState.Unavailable, settings.viewModel.session.state.value)
                val tab = application.openSession(historyIndex)
                assertSame(settings, application.popup.value)
                assertEquals(SessionSettingsState.Unavailable, settings.viewModel.session.state.value)
                val login = application.openLoginPopup(settings)
                assertSame(settings, login.returnTo)
                coroutineScope {
                    val effects = async(start = CoroutineStart.UNDISPATCHED) { login.viewModel.effects.firstOrNull() }
                    try {
                        assertFalse(effects.isCompleted)
                        assertTrue(application.closeTab(tab))
                        assertSame(login, application.popup.value)
                        assertFalse(effects.isCompleted)
                        assertTrue(application.dismissPopup(login))
                        assertNull(effects.await())
                    } finally { effects.cancelAndJoin() }
                }
                assertSame(settings, application.popup.value)
                assertNull(settings.target)
                assertEquals(SessionSettingsState.Unavailable, settings.viewModel.session.state.value)
                assertSame(singleton, application.newSession)
                application.shutdown()
                assertEquals(ComposerLifecycle.Closed, singleton.composer.state.value.lifecycle)
                assertFalse(settings.viewModel.newSession.state.value.active)
                assertEquals(ApplicationPopupState.Closed, application.popup.value)
            }
        }
    }

    test("root persisted Settings and Login retain the exact target rather than the latest selected tab") {
        withBoundaryRoot {
            val first = application.openSession(historyIndex)
            val second = application.openSession(services.global.createSession(root))
            second.rename("Second settings target")
            second.name.first { it == "Second settings target" }
            application.selectTab(first)
            runBoundaryMosaic {
                setContentAndSnapshot { SessionTreeCliScreen(application, newLineKey, sidebar) }
                clickBoundaryText(boundarySnapshot("[Settings]"), "[Settings]")
                awaitBoundaryCondition { application.popup.value is ApplicationPopupState.Settings }
                val settings = assertIs<ApplicationPopupState.Settings>(application.popup.value)
                assertSame(first, settings.target)
                assertEquals(SettingsPage.CurrentSession, settings.viewModel.selectedPage.value)
                val state = assertIs<SessionSettingsState.Available>(settings.viewModel.session.state.value)
                assertEquals("Root history", state.snapshot.sessionName)
                val secondSettings = second.settings.value
                application.selectTab(second)
                settings.viewModel.session.updateReasoningEffort(state.snapshot.revision, ReasoningEffort.Max)
                first.settings.first { it.reasoning.effort == ReasoningEffort.Max }
                assertEquals(secondSettings, second.settings.value, "An edit stays bound to the opening target.")
                val login = application.openLoginPopup(settings)
                assertSame(settings, login.returnTo)
                assertSame(first, login.returnTo.target)
                assertTrue(application.dismissPopup(login))
                assertSame(settings, application.popup.value)
                assertTrue(application.closeTab(second))
                assertSame(settings, application.popup.value)
                assertEquals("Root history",
                    assertIs<SessionSettingsState.Available>(settings.viewModel.session.state.value).snapshot.sessionName)
                assertTrue(application.closeTab(first))
                assertEquals(ApplicationPopupState.Closed, application.popup.value)
                assertFalse(settings.viewModel.newSession.state.value.active)
            }
        }
    }

    for (operation in listOf("close", "delete", "archive")) {
        test("$operation background selected and last real tabs preserve navigation singleton and draft") {
            withBoundaryRoot(seedHistory = false) {
                val singleton = application.newSession
                val first = application.openSession(services.global.createSession(root))
                val selected = application.openSession(services.global.createSession(root))
                val last = application.openSession(services.global.createSession(root))
                application.selectTab(selected)
                singleton.composer.update("retained local draft", 7)
                val input = singleton.composer.state.value
                val chosen = Path(root, "retained-cwd")
                Fs.createDirectories(chosen)
                singleton.updateWorkingDirectory(chosen)
                singleton.settings.first { it.cwd == chosen }
                assertTrue(remove(operation, first))
                assertSame(selected, application.navigation.value.selectedSession)
                assertEquals(ApplicationTabSelection.SelectedTab(0), application.navigation.value.selection)
                assertTrue(remove(operation, selected))
                assertSame(last, application.navigation.value.selectedSession)
                assertTrue(remove(operation, last))
                assertTrue(application.navigation.value.tabs.isEmpty())
                assertEquals(ApplicationTabSelection.NewSession, application.navigation.value.selection)
                assertSame(singleton, application.newSession)
                val added = services.global.createSession(root)
                val addedLast = services.global.createSession(root)
                application.openCreatedSessions(listOf(added, addedLast, added))
                assertEquals(ApplicationTabSelection.NewSession, application.navigation.value.selection)
                assertEquals(2, application.navigation.value.tabs.size)
                assertTrue(remove(operation, application.navigation.value.tabs.first()))
                assertEquals(ApplicationTabSelection.NewSession, application.navigation.value.selection)
                assertTrue(remove(operation, application.navigation.value.tabs.single()))
                assertTrue(application.navigation.value.tabs.isEmpty())
                assertNull(application.navigation.value.selectedSession)
                assertEquals(ApplicationTabSelection.NewSession, application.navigation.value.selection)
                assertSame(singleton, application.newSession)
                assertEquals(input, singleton.composer.state.value)
                assertEquals(chosen, singleton.settings.value.cwd)
                val catalog = services.global.getSessionCatalog(true)
                assertEquals(if (operation == "delete") 0 else 5, catalog.size)
                if (operation == "archive") assertTrue(services.global.getSessionCatalog(false).isEmpty())
            }
        }
    }

    test("closing the last local tab and frontend shutdown never Stop a running backend turn") {
        val running = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        var stops = 0
        try {
            withBoundaryRoot(seedHistory = false, response = {
                running.complete(Unit)
                try {
                    finish.await()
                    emit(ResponsesStreamEvent.Completed(Response(id = "done", endTurn = true)))
                } finally { ended.complete(Unit) }
            }, decorate = { original ->
                object : RpcClient by original {
                    override suspend fun <T> call(call: RpcCall): T {
                        if (call.callableName == "cancelRunningTurn") stops++
                        return original.call(call)
                    }
                }
            }) {
                val singleton = application.newSession
                singleton.composer.update("keep backend running")
                val created = assertNotNull(application.submitNewSession(singleton.composer.state.value.revision))
                running.await()
                assertNotNull(created.rootAgent.value).running.first { it }
                assertTrue(application.closeTab(created))
                assertNull(created.rootAgent.value)
                assertTrue(application.navigation.value.tabs.isEmpty())
                assertFalse(ended.isCompleted)
                assertTrue(services.global.getSessionCatalog(true).single().running)
                assertEquals(ComposerLifecycle.Open, singleton.composer.state.value.lifecycle)
                val settings = application.openSettingsPopup(null, SettingsPage.NewSession)
                val login = application.openLoginPopup(settings)
                coroutineScope {
                    val effects = async(start = CoroutineStart.UNDISPATCHED) { login.viewModel.effects.firstOrNull() }
                    try {
                        application.shutdown()
                        assertNull(effects.await())
                    } finally { effects.cancelAndJoin() }
                }
                assertEquals(ComposerLifecycle.Closed, singleton.composer.state.value.lifecycle)
                assertFalse(settings.viewModel.newSession.state.value.active)
                assertFalse(ended.isCompleted)
                assertEquals(0, stops)
                finish.complete(Unit)
                withTimeout(5.seconds) { ended.await() }
            }
        } finally { finish.complete(Unit) }
    }
}

private class IntegrationFailure(stage: String) : IllegalStateException("controlled $stage")

private suspend fun TestMosaic<String>.assertRootSelection(newSession: Boolean) {
    awaitBoundaryCondition {
        val frame = draw().render(AnsiLevel.ANSI16, supportsKittyUnderlines = false)
        rootLabelInverted(frame, "[+]") == newSession &&
            rootLabelInverted(frame, "[Root history]") == !newSession
    }
}

/** Inspect the root's actual reverse-video spans without depending on SGR color ordering. */
private fun rootLabelInverted(frame: String, label: String): Boolean? {
    val text = StringBuilder()
    val inverted = mutableListOf<Boolean>()
    var reverse = false
    var offset = 0
    fun append(endExclusive: Int) {
        for (index in offset until endExclusive) {
            text.append(frame[index])
            inverted += reverse
        }
    }
    for (sgr in Regex("\u001B\\[([0-9;]*)m").findAll(frame)) {
        append(sgr.range.first)
        for (parameter in sgr.groupValues[1].split(';')) {
            when (parameter.toIntOrNull() ?: 0) {
                0, 27 -> reverse = false
                7 -> reverse = true
            }
        }
        offset = sgr.range.last + 1
    }
    append(frame.length)
    val start = text.indexOf(label)
    if (start < 0) return null
    val span = inverted.subList(start, start + label.length)
    return when {
        span.all { it } -> true
        span.none { it } -> false
        else -> null
    }
}

private class IntegrationGate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    suspend fun wait() {
        entered.complete(Unit)
        release.await()
    }
}

private suspend fun BoundaryRoot.firstMessage(session: PersistedSessionViewModel): StableUserMessage =
    assertIs<StableUserMessage>(services.index.get(
        session.sessionIndex, services.index.getCacheNonce(session.sessionIndex), 1,
    ))

private suspend fun PersistedSessionViewModel.awaitMockAnswer() {
    val agent = assertNotNull(rootAgent.value)
    withTimeout(5.seconds) {
        agent.state.first { it == AgentStateValue.AssistantMessage }
        agent.running.first { !it }
    }
}

private suspend fun BoundaryRoot.remove(operation: String, target: PersistedSessionViewModel): Boolean =
    when (operation) {
        "close" -> application.closeTab(target)
        "delete" -> application.deleteSession(target.sessionIndex)
        "archive" -> application.closeAndArchiveSession(target)
        else -> error("Unknown operation $operation")
    }
