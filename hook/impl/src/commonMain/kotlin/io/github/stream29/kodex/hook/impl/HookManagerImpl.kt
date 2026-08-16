package io.github.stream29.kodex.hook.impl

import io.github.stream29.kodex.hook.contract.HookBody
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookConfigurationStore
import io.github.stream29.kodex.hook.contract.HookDraft
import io.github.stream29.kodex.hook.contract.HookManagedState
import io.github.stream29.kodex.hook.contract.HookManager
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Default application-wide [HookManager] implementation. */
public class HookManagerImpl internal constructor(
    scope: CoroutineScope,
    private val store: HookConfigurationStore,
) : HookManager {
    private val scope = scope.supervisorChildScope()
    private val commandMutex = Mutex()
    private var closed = false

    override val hooks: StateFlow<List<HookManagedState>> =
        store.configuration
            .map { configuration -> configuration.toManagedHooks() }
            .stateIn(
                scope = this.scope,
                started = SharingStarted.Eagerly,
                initialValue = store.configuration.value.toManagedHooks(),
            )

    override suspend fun add(draft: HookDraft): String =
        command {
            val normalized = draft.normalize()
            store.update { current ->
                require(normalized.name !in current) {
                    "Hook '${normalized.name}' already exists."
                }
                buildMap {
                    putAll(current)
                    put(normalized.name, normalized.body)
                }
            }
            normalized.name
        }

    override suspend fun edit(name: String, draft: HookDraft) {
        command {
            require(name.isNotBlank()) { "A Hook name must not be blank." }
            val normalized = draft.normalize()
            store.update { current ->
                require(name in current) { "Hook '$name' does not exist." }
                require(normalized.name == name || normalized.name !in current) {
                    "Hook '${normalized.name}' already exists."
                }
                buildMap {
                    current.forEach { (currentName, body) ->
                        if (currentName == name) {
                            put(normalized.name, normalized.body)
                        } else {
                            put(currentName, body)
                        }
                    }
                }
            }
        }
    }

    override suspend fun delete(name: String) {
        command {
            store.update { current ->
                require(name in current) { "Hook '$name' does not exist." }
                buildMap {
                    current.forEach { (currentName, body) ->
                        if (currentName != name) put(currentName, body)
                    }
                }
            }
        }
    }

    override fun editorDraft(name: String): HookDraft? {
        if (closed) return null
        return store.configuration.value[name]?.let { body ->
            HookDraft(
                name = name,
                type = body.type,
                command = body.command,
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
    }

    private suspend fun <Value> command(block: suspend () -> Value): Value {
        check(!closed) { "The Hook manager is closed." }
        return commandMutex.withLock { block() }
    }
}

/** Creates a manager whose lifetime is a child of this scope. */
public fun CoroutineScope.HookManagerImpl(
    store: HookConfigurationStore,
): HookManagerImpl =
    HookManagerImpl(
        scope = this,
        store = store,
    )

private data class NormalizedHookDraft(
    val name: String,
    val body: HookBody,
)

private fun HookDraft.normalize(): NormalizedHookDraft {
    val normalizedName = name.trim()
    require(normalizedName.isNotEmpty()) { "A Hook name must not be blank." }
    return NormalizedHookDraft(
        name = normalizedName,
        body = HookBody(
            type = type,
            command = command,
        ),
    )
}

private fun HookConfiguration.toManagedHooks(): List<HookManagedState> =
    map { (name, body) ->
        HookManagedState(
            name = name,
            type = body.type,
        )
    }
