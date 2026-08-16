package io.github.stream29.kodex.hook.impl

import io.github.stream29.kodex.hook.contract.HookBody
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookConfigurationStore
import io.github.stream29.kodex.hook.contract.HookDraft
import io.github.stream29.kodex.hook.contract.HookManagedState
import io.github.stream29.kodex.hook.contract.HookType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HookManagerImplTest {
    @Test
    fun addPublishesHooksInPersistedOrder() = runTest {
        val store = TestHookConfigurationStore()
        val manager = backgroundScope.HookManagerImpl(store)

        val firstName = manager.add(
            HookDraft(
                name = "  guard tools  ",
                type = HookType.PreToolUse,
                command = "guard-command",
            ),
        )
        manager.add(
            HookDraft(
                name = "verify stop",
                type = HookType.Stop,
                command = "stop-command",
            ),
        )
        runCurrent()

        assertEquals("guard tools", firstName)
        assertEquals(
            listOf(
                HookManagedState("guard tools", HookType.PreToolUse),
                HookManagedState("verify stop", HookType.Stop),
            ),
            manager.hooks.value,
        )
        assertEquals(listOf("guard tools", "verify stop"), store.configuration.value.keys.toList())
        assertEquals(
            HookDraft("guard tools", HookType.PreToolUse, "guard-command"),
            manager.editorDraft("guard tools"),
        )
        manager.close()
        assertNull(manager.editorDraft("guard tools"))
    }

    @Test
    fun editRenamesHookWithoutChangingItsPosition() = runTest {
        val store = TestHookConfigurationStore(
            linkedMapOf(
                "first" to HookBody(HookType.PreToolUse, "first-command"),
                "second" to HookBody(HookType.Stop, "second-command"),
            ),
        )
        val manager = backgroundScope.HookManagerImpl(store)

        manager.edit(
            name = "first",
            draft = HookDraft(
                name = "renamed",
                type = HookType.UserPromptSubmit,
                command = "updated-command",
            ),
        )
        runCurrent()

        assertEquals(listOf("renamed", "second"), store.configuration.value.keys.toList())
        assertEquals(
            HookBody(HookType.UserPromptSubmit, "updated-command"),
            store.configuration.value.getValue("renamed"),
        )
        manager.delete("renamed")
        runCurrent()
        assertEquals(listOf("second"), manager.hooks.value.map(HookManagedState::name))
        manager.close()
    }

    @Test
    fun invalidOrConflictingDraftDoesNotWriteSettings() = runTest {
        val store = TestHookConfigurationStore(
            linkedMapOf(
                "first" to HookBody(HookType.PreToolUse, "first-command"),
                "second" to HookBody(HookType.Stop, "second-command"),
            ),
        )
        val manager = backgroundScope.HookManagerImpl(store)
        val initial = store.configuration.value

        assertFailsWith<IllegalArgumentException> {
            manager.add(HookDraft(" ", HookType.Stop, "command"))
        }
        assertFailsWith<IllegalArgumentException> {
            manager.add(HookDraft("third", HookType.Stop, " "))
        }
        assertFailsWith<IllegalArgumentException> {
            manager.add(HookDraft("first", HookType.Stop, "duplicate"))
        }
        assertFailsWith<IllegalArgumentException> {
            manager.edit(
                "first",
                HookDraft("second", HookType.PreToolUse, "collision"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            manager.delete("missing")
        }

        assertEquals(initial, store.configuration.value)
        assertEquals(0, store.successfulUpdateCount)
        manager.close()
    }
}

private class TestHookConfigurationStore(
    initial: HookConfiguration = emptyMap(),
) : HookConfigurationStore {
    private val mutex = Mutex()
    override val configuration = MutableStateFlow(initial)
    var successfulUpdateCount: Int = 0

    override suspend fun update(
        transform: (HookConfiguration) -> HookConfiguration,
    ): HookConfiguration =
        mutex.withLock {
            val updated = transform(configuration.value)
            configuration.value = updated
            successfulUpdateCount += 1
            updated
        }
}
