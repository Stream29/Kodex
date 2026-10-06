package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
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
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opening or editing this object does not allocate a backend Session. */
public class RpcSessionDraft(
    initialSettings: KodexAgentSettings,
    private val views: RpcSessionViews,
) : AutoCloseable {
    private val mutex = Mutex()
    private val mutableSettings = MutableStateFlow(initialSettings)
    public val settings: StateFlow<KodexAgentSettings> = mutableSettings.asStateFlow()
    private val mutableEditable = MutableStateFlow(true)
    public val editable: StateFlow<Boolean> = mutableEditable.asStateFlow()
    private val composerScope = CoroutineScope(SupervisorJob())
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
    private var createdIndex: Int? = null
    private var submitted = false
    private var explicitlyNamed = false
    private var renameAttempted = false

    public suspend fun edit(transform: (KodexAgentSettings) -> KodexAgentSettings): Unit = mutex.withLock {
        check(!closed && createdIndex == null) { "The draft is no longer editable." }
        val next = transform(settings.value)
        if (next.threadName != settings.value.threadName) explicitlyNamed = true
        mutableSettings.value = next
    }

    public suspend fun clearExplicitThreadName(): Unit = mutex.withLock {
        check(!closed && createdIndex == null) { "The draft is no longer editable." }
        explicitlyNamed = false
        mutableSettings.value = mutableSettings.value.copy(threadName = "")
    }

    /** Creation and initial append are distinct commits; failure never rolls back the entity. */
    public suspend fun materialize(): RpcSessionView = mutex.withLock {
        check(!closed)
        val captured = composer.state.value
        val index = createdIndex ?: views.services.global.createSession(settings.value).also {
            createdIndex = it
            mutableEditable.value = false
        }
        check(!closed) { "The draft was closed while creating its Session." }
        val view = views.open(index)
        if (explicitlyNamed && !renameAttempted) {
            renameAttempted = true
            val current = view.current().settings
            current.editField(current.value.threadName, { it.threadName }, { it.copy(threadName = settings.value.threadName) })
        }
        if (!submitted && captured.text.isNotBlank()) {
            view.current().appendUserMessage(listOf(ContentItem.InputText(captured.text.trim())))
            submitted = true
            composer.clear(captured.revision)
            requireNotNull(view.agent.value).resume()
        }
        view
    }

    public val persistedIndex: Int? get() = createdIndex
    override fun close() {
        closed = true
        mutableEditable.value = false
        composer.close()
        composerScope.cancel()
    }
}
