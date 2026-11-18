@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.AgentNotActive
import io.github.stream29.kodex.utils.rpcexception.AgentNotFound
import io.github.stream29.kodex.utils.rpcexception.ShellSessionNotRegistered
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

private const val ReadLimit = 65_536u
private const val IdleMillis = 200L

public actual class TerminalOutputReader public actual constructor(
    private val session: AgentShellSession,
    private val registry: AgentShellSessionRegistry,
) {
    private val started = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(TerminalOutputState(emptyList(), TerminalOutputPhase.Reading))
    public actual val state: StateFlow<TerminalOutputState> = mutableState.asStateFlow()

    private fun registered(): Boolean = registry.activeSessions.value[session.sessionId] === session

    public actual suspend fun observe(): Unit = coroutineScope {
        check(started.compareAndSet(expect = false, update = true)) { "The inspector already has a reader." }
        if (!registered()) {
            finish(TerminalOutputPhase.Removed)
            return@coroutineScope
        }
        val worker = async { readChunks() }
        val membership = launch(start = CoroutineStart.UNDISPATCHED) {
            registry.activeSessions.first { it[session.sessionId] !== session }
            finish(TerminalOutputPhase.Removed)
            worker.cancel()
        }
        try {
            worker.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (state.value.phase != TerminalOutputPhase.Removed) throw cancelled
        } finally {
            membership.cancelAndJoin()
        }
    }

    private fun finish(phase: TerminalOutputPhase) {
        mutableState.update {
            if (it.phase == TerminalOutputPhase.Reading) TerminalOutputState(it.items, phase) else it
        }
    }

    private suspend fun readChunks() {
        val window = TerminalOutputWindow()
        val decoder = TerminalOutputDecoder(window::append)
        var offset = 0uL
        var total = 0uL
        try {
            while (registered()) {
                val exitBeforeRead = session.exitCode.value
                val slice = session.readOutput(offset, ReadLimit)
                currentCoroutineContext().ensureActive()
                if (!registered()) break
                slice.checkCursor(offset, total)
                decoder.feed(slice.head)
                if (slice.omittedByteCount != 0uL) {
                    decoder.finish()
                    window.omit(slice.omittedByteCount)
                }
                decoder.feed(slice.tail)
                offset = slice.nextOffset
                total = slice.totalByteCount
                val drained = exitBeforeRead != null && offset == total
                if (drained) decoder.finish()
                val items = window.takeSnapshot()
                if (items != null || drained) {
                    mutableState.update { old ->
                        if (old.phase != TerminalOutputPhase.Reading) old else TerminalOutputState(
                            items ?: old.items,
                            if (drained) TerminalOutputPhase.Complete(checkNotNull(exitBeforeRead)) else old.phase,
                        )
                    }
                }
                if (drained) return
                if (offset != total) yield()
                else withTimeoutOrNull(IdleMillis) { session.exitCode.first { it != null } }
            }
            finish(TerminalOutputPhase.Removed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ShellSessionNotRegistered) {
            finish(TerminalOutputPhase.Removed)
        } catch (_: SessionNotActive) {
            finish(TerminalOutputPhase.SessionInactive)
        } catch (_: AgentNotActive) {
            finish(TerminalOutputPhase.SessionInactive)
        } catch (_: AgentNotFound) {
            finish(TerminalOutputPhase.SessionInactive)
        } catch (_: Exception) {
            finish(TerminalOutputPhase.ReadFailed)
        }
    }
}

private fun StdoutSlice.checkCursor(expected: ULong, previousTotal: ULong) {
    check(offset == expected && totalByteCount >= previousTotal && nextOffset in offset..totalByteCount)
    val retained = head.size.toULong() + tail.size.toULong()
    val advance = nextOffset - offset
    check(retained <= ReadLimit.toULong() && retained <= advance && advance - retained == omittedByteCount)
    check(nextOffset > offset || nextOffset == totalByteCount)
}
