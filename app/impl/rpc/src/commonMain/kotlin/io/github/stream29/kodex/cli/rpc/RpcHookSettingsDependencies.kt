package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.hooksettings.HookEditHandle
import io.github.stream29.kodex.app.hooksettings.HookSettingsDependencies
import io.github.stream29.kodex.app.hooksettings.HookWriteAdmission
import io.github.stream29.kodex.rpc.models.NotificationHook
import kotlinx.coroutines.flow.StateFlow

/** Binds local frontend persistence to the shared application queue, never a component-owned job. */
internal class RpcHookSettingsDependencies(
    private val global: RpcGlobalSettings,
    override val hooks: StateFlow<List<NotificationHook>>,
    private val accept: (suspend () -> Unit) -> Boolean,
) : HookSettingsDependencies {
    override val operationFailure: StateFlow<Boolean> = global.operationFailure

    override fun captureEditor(name: String?): HookEditHandle? {
        val original = name?.let { target ->
            global.frontend.settings.value.hooks.find { it.name == target } ?: return null
        }
        return object : HookEditHandle {
            override val original: NotificationHook? = original
            private var released = false
            override fun save(updated: NotificationHook): HookWriteAdmission {
                if (released) return HookWriteAdmission.Rejected("The Hook editor is no longer active.")
                // Copy intent into the queue before component release can discard the editor.
                val accepted = accept {
                    global.frontend.update { value ->
                        val current = value.hooks.find { it.name == (original?.name ?: updated.name) }
                        if (current != original) value
                        else value.copy(hooks = if (original == null) value.hooks + updated
                        else value.hooks.map { if (it.name == original.name) updated else it })
                    }
                }
                if (accepted) released = true
                return admission(accepted)
            }
            override fun release() { released = true }
        }
    }

    override fun delete(original: NotificationHook): HookWriteAdmission = admission(accept {
        global.frontend.update { value ->
            if (value.hooks.find { it.name == original.name } != original) value
            else value.copy(hooks = value.hooks.filterNot { it.name == original.name })
        }
    })

    override fun reportFailure(failure: Throwable) { global.reportOperationFailure(failure) }
    override fun dismissFailure() { global.dismissOperationFailure() }
}

private fun admission(accepted: Boolean): HookWriteAdmission =
    if (accepted) HookWriteAdmission.Accepted
    else HookWriteAdmission.Rejected("Settings is no longer accepting edits.")
