package io.github.stream29.codex.lite.utils

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.utils.ReadWriteMutex.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue



val readWriteMutexTest by testSuite {
    test("multiple readers can hold the lock together") {
        val mutex = ReadWriteMutex()

        mutex.reader.lock()
        mutex.reader.lock()

        assertEquals(State.Read(2), mutex.stateFlow.value)
        assertFalse(mutex.writer.tryLock())

        mutex.reader.unlock()
        assertEquals(State.Read(1), mutex.stateFlow.value)

        mutex.reader.unlock()
        assertEquals(State.Free, mutex.stateFlow.value)

        assertTrue(mutex.writer.tryLock())
        mutex.writer.unlock()
    }

    test("writer blocks new readers while waiting for existing readers") {
        val mutex = ReadWriteMutex()
        val writerStarted = CompletableDeferred<Unit>()
        val writerAcquired = CompletableDeferred<Unit>()

        mutex.reader.lock()
        val writerJob = launch(start = CoroutineStart.UNDISPATCHED) {
            writerStarted.complete(Unit)
            mutex.writer.lock()
            writerAcquired.complete(Unit)
        }

        writerStarted.await()
        assertFalse(mutex.reader.tryLock())
        assertEquals(State.Read(1), mutex.stateFlow.value)

        mutex.reader.unlock()
        writerAcquired.await()
        assertEquals(State.Write, mutex.stateFlow.value)

        mutex.writer.unlock()
        writerJob.join()
        assertEquals(State.Free, mutex.stateFlow.value)
    }

    test("writer try lock failure does not block future readers") {
        val mutex = ReadWriteMutex()

        mutex.reader.lock()
        assertFalse(mutex.writer.tryLock())

        assertTrue(mutex.reader.tryLock())
        assertEquals(State.Read(2), mutex.stateFlow.value)

        mutex.reader.unlock()
        mutex.reader.unlock()
        assertEquals(State.Free, mutex.stateFlow.value)
    }

    test("cancelled writer wait does not block future readers") {
        val mutex = ReadWriteMutex()

        mutex.reader.lock()
        val writerJob = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.writer.lock()
        }

        assertFalse(mutex.reader.tryLock())

        writerJob.cancelAndJoin()
        assertTrue(mutex.reader.tryLock())
        assertEquals(State.Read(2), mutex.stateFlow.value)

        mutex.reader.unlock()
        mutex.reader.unlock()
        assertEquals(State.Free, mutex.stateFlow.value)
    }

    test("cancelled reader wait does not leave read state behind") {
        val mutex = ReadWriteMutex()

        mutex.writer.lock()
        val readerJob = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.reader.lock()
        }

        readerJob.cancelAndJoin()
        assertEquals(State.Write, mutex.stateFlow.value)

        mutex.writer.unlock()
        assertEquals(State.Free, mutex.stateFlow.value)
        assertTrue(mutex.writer.tryLock())
        mutex.writer.unlock()
    }

    test("writer waits until all readers release") {
        val mutex = ReadWriteMutex()

        mutex.reader.lock()
        mutex.reader.lock()
        val writerDeferred = async {
            mutex.writer.lock()
            mutex.stateFlow.value
        }

        yield()
        assertFalse(writerDeferred.isCompleted)

        mutex.reader.unlock()
        yield()
        assertFalse(writerDeferred.isCompleted)

        mutex.reader.unlock()
        assertEquals(State.Write, writerDeferred.await())

        mutex.writer.unlock()
        assertEquals(State.Free, mutex.stateFlow.value)
    }

    test("try lock transitions state consistently") {
        val mutex = ReadWriteMutex()

        assertTrue(mutex.reader.tryLock())
        assertIs<State.Read>(mutex.stateFlow.value)
        mutex.reader.unlock()

        assertTrue(mutex.writer.tryLock())
        assertEquals(State.Write, mutex.stateFlow.value)
        assertFalse(mutex.reader.tryLock())

        mutex.writer.unlock()
        assertEquals(State.Free, mutex.stateFlow.value)
    }
}
