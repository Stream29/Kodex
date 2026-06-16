package io.github.stream29.codex.lite.utils

import io.github.stream29.codex.lite.utils.ReadWriteMutex.State
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SafeRwTest {
    @Test
    fun writeSessionMutatesValueAndReadSessionSeesImmutableView() = runTest {
        val safe = SafeRw<ImmutableListHolder, MutableListHolder>(MutableListHolder())

        safe.writeSession { value ->
            value.add("one")
            value.add("two")
        }

        var observedValues = emptyList<String>()
        var observedVersion = 0
        safe.readSession { value ->
            observedValues = value.values
            observedVersion = value.version
        }

        assertEquals(listOf("one", "two"), observedValues)
        assertEquals(2, observedVersion)
        assertEquals(State.Free, safe.rwMutex.stateFlow.value)
    }

    @Test
    fun readSessionHoldsReaderLock() = runTest {
        val safe = SafeRw<ImmutableListHolder, MutableListHolder>(MutableListHolder())

        safe.readSession {
            assertEquals(State.Read(1), safe.rwMutex.stateFlow.value)
            assertFalse(safe.rwMutex.writer.tryLock())
        }

        assertEquals(State.Free, safe.rwMutex.stateFlow.value)
        assertTrue(safe.rwMutex.writer.tryLock())
        safe.rwMutex.writer.unlock()
    }

    @Test
    fun writeSessionHoldsWriterLock() = runTest {
        val safe = SafeRw<ImmutableListHolder, MutableListHolder>(MutableListHolder())

        safe.writeSession { value ->
            assertEquals(State.Write, safe.rwMutex.stateFlow.value)
            assertFalse(safe.rwMutex.reader.tryLock())
            value.add("value")
        }

        assertEquals(State.Free, safe.rwMutex.stateFlow.value)
        safe.readSession { value ->
            assertEquals(listOf("value"), value.values)
        }
    }

    @Test
    fun writeSessionWaitsForExistingReader() = runTest {
        val safe = SafeRw<ImmutableListHolder, MutableListHolder>(MutableListHolder())

        safe.rwMutex.reader.lock()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
            safe.writeSession { value ->
                value.add("written")
            }
        }

        yield()
        assertFalse(writer.isCompleted)
        assertEquals(State.Read(1), safe.rwMutex.stateFlow.value)

        safe.rwMutex.reader.unlock()
        writer.await()

        assertEquals(State.Free, safe.rwMutex.stateFlow.value)
        safe.readSession { value ->
            assertEquals(listOf("written"), value.values)
        }
    }

    @Test
    fun readSessionWaitsForExistingWriter() = runTest {
        val safe = SafeRw<ImmutableListHolder, MutableListHolder>(MutableListHolder())

        safe.rwMutex.writer.lock()
        val reader = async(start = CoroutineStart.UNDISPATCHED) {
            safe.readSession { value ->
                value.values
            }
        }

        yield()
        assertFalse(reader.isCompleted)
        assertEquals(State.Write, safe.rwMutex.stateFlow.value)

        safe.rwMutex.writer.unlock()
        reader.await()

        assertEquals(State.Free, safe.rwMutex.stateFlow.value)
    }

    @Test
    fun sessionsReleaseLockWhenBlockThrows() = runTest {
        val safe = SafeRw<ImmutableListHolder, MutableListHolder>(MutableListHolder())

        val readFailure = runCatching {
            safe.readSession {
                error("read failed")
            }
        }.exceptionOrNull()

        assertIs<IllegalStateException>(readFailure)
        assertEquals(State.Free, safe.rwMutex.stateFlow.value)

        val writeFailure = runCatching {
            safe.writeSession { value ->
                value.add("before failure")
                throw IllegalArgumentException("write failed")
            }
        }.exceptionOrNull()

        assertIs<IllegalArgumentException>(writeFailure)
        assertEquals(State.Free, safe.rwMutex.stateFlow.value)
        safe.readSession { value ->
            assertEquals(listOf("before failure"), value.values)
        }
    }
}

private interface ImmutableListHolder {
    val values: List<String>
    val version: Int
}

private class MutableListHolder : ImmutableListHolder {
    private val backingValues = mutableListOf<String>()

    override val values: List<String>
        get() = backingValues.toList()

    override var version: Int = 0
        private set

    fun add(value: String) {
        backingValues += value
        version++
    }
}
