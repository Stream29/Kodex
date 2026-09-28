package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.cli.agent.DefaultComposerViewModelFactory
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    public val composer: ComposerViewModel = DefaultComposerViewModelFactory.create()
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
            requireNotNull(view.presentation.value).resume()
        }
        view
    }

    public val persistedIndex: Int? get() = createdIndex
    override fun close() { closed = true; mutableEditable.value = false; composer.close() }
}
