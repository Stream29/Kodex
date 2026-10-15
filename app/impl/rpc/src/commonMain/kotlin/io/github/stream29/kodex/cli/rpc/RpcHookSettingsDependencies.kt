package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.hooksettings.HookEditHandle
import io.github.stream29.kodex.app.hooksettings.HookSettingsDependencies
import io.github.stream29.kodex.app.hooksettings.HookWriteAdmission
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

/** Binds local frontend persistence to the shared application queue, never a component-owned job. */
internal class RpcHookSettingsDependencies(
    private val global: RpcGlobalSettings,
    override val hooks: StateFlow<List<NotificationHook>>,
    private val backend: Boolean = false,
    private val accept: (suspend () -> Unit) -> Boolean,
) : HookSettingsDependencies {
    override val operationFailure: StateFlow<Boolean> = global.operationFailure
    override val supportedTypes: Set<NotificationHookType> =
        if (backend) NotificationHookType.entries.toSet() else setOf(NotificationHookType.StopUnhandledError)

    private suspend fun change(transform: (List<NotificationHook>) -> List<NotificationHook>) {
        if (!backend) {
            global.frontend.update { it.copy(hooks = transform(it.hooks)) }
            return
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val old = global.settings.value
            val updated = transform(old.hooks)
            if (updated == old.hooks) return
            if (global.settings.compareAndSet(old, old.copy(hooks = updated))) return
            delay(50)
        }
    }

    override fun captureEditor(name: String?): HookEditHandle? {
        val original = name?.let { target ->
            hooks.value.find { it.name == target } ?: return null
        }
        return object : HookEditHandle {
            override val original: NotificationHook? = original
            private var released = false
            override fun save(updated: NotificationHook): HookWriteAdmission {
                if (released) return HookWriteAdmission.Rejected("The Hook editor is no longer active.")
                // Copy intent into the queue before component release can discard the editor.
                val accepted = accept {
                    change { value ->
                        val current = value.find { it.name == (original?.name ?: updated.name) }
                        if (current != original) value
                        else if (original == null) value + updated
                        else value.map { if (it.name == original.name) updated else it }
                    }
                }
                if (accepted) released = true
                return admission(accepted)
            }
            override fun release() { released = true }
        }
    }

    override fun delete(original: NotificationHook): HookWriteAdmission = admission(accept {
        change { value ->
            if (value.find { it.name == original.name } != original) value
            else value.filterNot { it.name == original.name }
        }
    })

    override fun reportFailure(failure: Throwable) { global.reportOperationFailure(failure) }
    override fun dismissFailure() { global.dismissOperationFailure() }
}

private fun admission(accepted: Boolean): HookWriteAdmission =
    if (accepted) HookWriteAdmission.Accepted
    else HookWriteAdmission.Rejected("Settings is no longer accepting edits.")
