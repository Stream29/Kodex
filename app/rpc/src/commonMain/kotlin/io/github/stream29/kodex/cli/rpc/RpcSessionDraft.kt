package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.agent.contract.AgentLifecycleState
import io.github.stream29.kodex.app.agent.contract.ComposerDependencies
import io.github.stream29.kodex.app.agent.contract.ComposerFailureReporter
import io.github.stream29.kodex.app.agent.contract.ComposerOwnerId
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPort
import io.github.stream29.kodex.app.agent.contract.ComposerRequestInputPresentation
import io.github.stream29.kodex.app.agent.contract.ComposerResumePort
import io.github.stream29.kodex.app.agent.contract.ComposerRuntimePort
import io.github.stream29.kodex.app.agent.contract.ComposerSteerPort
import io.github.stream29.kodex.app.agent.contract.ComposerSubmitPort
import io.github.stream29.kodex.app.agent.contract.ComposerCancellationPort
import io.github.stream29.kodex.cli.agent.createComposerViewModel
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.Reasoning
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.files.Path

/**
 * Opening or editing this object does not allocate a backend Session.
 * Its original local owner is a child of [ownerScope]; parent cancellation/join
 * closes Composer and its observations. Explicit close cancels only this child.
 * Views, global observations and the shared backend are borrowed, never closed or stopped here.
 * Submission runs in the host's surviving command owner; this object adds no detached work
 * or secondary error outlet.
 */
public class RpcSessionDraft(
    private val initialWorkingDirectory: Path,
    private val views: RpcSessionViews,
    private val global: RpcGlobalSettings,
    ownerScope: CoroutineScope,
) : AutoCloseable {
    private val submission = Mutex()
    private data class WorkingDirectory(val path: Path, val revision: Long)
    private val directory = MutableStateFlow(WorkingDirectory(initialWorkingDirectory, 0))
    private val owner = SupervisorJob(requireNotNull(ownerScope.coroutineContext[Job]) {
        "A draft requires an explicit coroutine owner."
    })
    private val composerScope = CoroutineScope(ownerScope.coroutineContext + owner)
    public val settings: StateFlow<KodexAgentSettings> =
        combine(global.settings.projectState { it.newSession }, directory) { defaults, cwd -> project(defaults, cwd.path) }
            .stateIn(composerScope, SharingStarted.Eagerly, project(global.settings.value.newSession, initialWorkingDirectory))

    private fun project(defaults: KodexNewSessionSettings, cwd: Path): KodexAgentSettings =
        KodexAgentSettings(
            model = defaults.model,
            reasoning = Reasoning(effort = defaults.reasoningEffort),
            serviceTier = defaults.serviceTier,
            requestUserInputMode = defaults.requestUserInputMode,
            cwd = cwd,
        )
    public val composer: ComposerViewModel = createComposerViewModel(
        ownerId = ComposerOwnerId("session-draft"),
        dependencies = object : ComposerDependencies {
            override val runtime = object : ComposerRuntimePort {
                override val running = MutableStateFlow(false)
                override val pendingSteer = MutableStateFlow(emptyList<io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent.Steerable>())
            }
            override val submit = ComposerSubmitPort { _, _ ->
                error("A draft must be submitted through its New Session owner.")
            }
            override val steer = ComposerSteerPort { _, _ ->
                error("A non-persisted draft cannot steer an Agent.")
            }
            override val cancellation = ComposerCancellationPort {}
            override val resume = ComposerResumePort {}
            override val requestInput = object : ComposerRequestInputPort {
                override val presentation = MutableStateFlow<ComposerRequestInputPresentation>(
                    ComposerRequestInputPresentation.None,
                )
            }
            override val failures = ComposerFailureReporter { _, _ -> }
        },
        ownerScope = composerScope,
    )
    private var closed = false

    init {
        owner.invokeOnCompletion { close() }
    }

    private fun checkOpen() {
        check(!closed) { "The draft is closed." }
        owner.ensureActive()
    }

    /** Freeze only this intent's target fields, preserving unrelated fields on CAS retry. */
    public suspend fun <F> editDefaults(
        select: (KodexNewSessionSettings) -> F,
        replace: (KodexNewSessionSettings) -> KodexNewSessionSettings,
    ) {
        checkOpen()
        val initial = select(global.settings.value.newSession)
        check(global.settings.editField(initial, { select(it.newSession) }, { it.copy(newSession = replace(it.newSession)) }) {
            checkOpen()
            global.ensureActive()
        }) { "The edited field changed. Review the latest value." }
    }

    public fun updateWorkingDirectory(path: Path) {
        checkOpen()
        directory.update {
            check(it.revision < Long.MAX_VALUE) { "Working directory revisions exhausted." }
            WorkingDirectory(path, it.revision + 1)
        }
    }

    /**
     * [open] opens the host's stable persisted handle before any append; RPC-only consumers may
     * supply [RpcSessionViews.open]. Creation and append remain distinct commits. The caller
     * owns accepted work and errors, so this suspends directly rather than launching hidden work.
     */
    public suspend fun <T> submit(expectedRevision: Long, open: suspend (Int) -> T): T? {
        checkOpen()
        // tryLock is admission, not a queue: a duplicate cannot wait and consume a later draft.
        if (!submission.tryLock()) return null
        try {
            currentCoroutineContext().ensureActive()
            checkOpen()
            val captured = composer.state.value
            if (captured.lifecycle != ComposerLifecycle.Open ||
                captured.revision != expectedRevision || captured.text.length == 0
            ) return null
            val cwd = directory.value
            val index = views.services.global.createSession(cwd.path)
            checkOpen()
            val persisted = open(index)
            checkOpen()
            val view = views.open(index)
            val binding = view.current()
            val agent = requireNotNull(view.agent.value)
            binding.appendUserMessage(listOf(ContentItem.InputText(captured.text)))
            currentCoroutineContext().ensureActive()
            checkOpen()
            binding.ensureActive()
            check(view.binding.value === binding && view.agent.value === agent &&
                agent.lifecycle.value == AgentLifecycleState.Open
            ) { "The created Session binding changed before resume admission." }
            agent.resume()
            checkOpen()
            composer.clear(captured.revision)
            // Compare revisions as well as paths: A -> B -> A is still a later directory edit.
            directory.compareAndSet(cwd, WorkingDirectory(initialWorkingDirectory, cwd.revision))
            return persisted
        } finally {
            submission.unlock()
        }
    }

    override fun close() {
        closed = true
        composer.close()
        composerScope.cancel()
    }
}
