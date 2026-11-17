package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.ComposerDependencies
import io.github.stream29.kodex.app.agent.contract.ComposerFailure
import io.github.stream29.kodex.app.agent.contract.ComposerLifecycle
import io.github.stream29.kodex.app.agent.contract.ComposerOperation
import io.github.stream29.kodex.app.agent.contract.ComposerOwnerId
import io.github.stream29.kodex.app.agent.contract.ComposerState
import io.github.stream29.kodex.app.agent.contract.ComposerSubmissionState
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public actual fun createComposerViewModel(
    ownerId: ComposerOwnerId,
    dependencies: ComposerDependencies,
    ownerScope: CoroutineScope,
): ComposerViewModel = ComposerViewModelImpl(ownerId, dependencies, ownerScope)

/**
 * Owns the Composer draft and command admission without constructing an Agent or repository.
 *
 * Runtime projections are observed only to publish renderer branches. Every command receives the
 * captured owner id, so a late completion can never select a different current owner.
 */
private class ComposerViewModelImpl(
    ownerId: ComposerOwnerId,
    private val dependencies: ComposerDependencies,
    ownerScope: CoroutineScope,
) : ComposerViewModel {
    private val mutableState = MutableStateFlow(
        ComposerState(
            ownerId = ownerId,
            running = dependencies.runtime.running.value,
            pendingSteer = dependencies.runtime.pendingSteer.value,
            requestInput = dependencies.requestInput.presentation.value,
        ),
    )
    private val submissionMutex = Mutex()
    private val observations = mutableListOf<Job>()
    private var closed = false

    override val state: StateFlow<ComposerState> = mutableState.asStateFlow()

    init {
        observations += ownerScope.launch {
            dependencies.runtime.running.collect { running ->
                updateProjection { it.copy(running = running) }
            }
        }
        observations += ownerScope.launch {
            dependencies.runtime.pendingSteer.collect { pending ->
                updateProjection { it.copy(pendingSteer = pending) }
            }
        }
        observations += ownerScope.launch {
            dependencies.requestInput.presentation.collect { presentation ->
                updateProjection { it.copy(requestInput = presentation) }
            }
        }
        ownerScope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    override fun clear(expectedRevision: Long): Boolean {
        if (closed) return false
        while (true) {
            val current = mutableState.value
            if (
                current.lifecycle == ComposerLifecycle.Closed ||
                current.revision != expectedRevision ||
                current.submission is ComposerSubmissionState.Submitting
            ) {
                return false
            }
            if (current.text.isEmpty()) return true
            val cleared = current.copy(
                text = "",
                revision = current.nextRevision(),
                cursorOffset = 0,
                submission = ComposerSubmissionState.Editing,
            )
            if (mutableState.compareAndSet(current, cleared)) return true
        }
    }

    override fun update(text: String, cursorOffset: Int): Long {
        require(cursorOffset in 0..text.length) {
            "Composer cursor offset must be within the draft."
        }
        check(!closed) { "The Composer is closed." }
        while (true) {
            val current = mutableState.value
            if (current.lifecycle == ComposerLifecycle.Closed) {
                error("The Composer is closed.")
            }
            if (current.submission is ComposerSubmissionState.Submitting) {
                return current.revision
            }
            val changed = current.text != text || current.cursorOffset != cursorOffset
            val clearsFailure = current.submission is ComposerSubmissionState.Failed
            if (!changed && !clearsFailure) return current.revision
            val updated = current.copy(
                text = text,
                cursorOffset = cursorOffset,
                revision = current.nextRevision(),
                submission = ComposerSubmissionState.Editing,
            )
            if (mutableState.compareAndSet(current, updated)) return updated.revision
        }
    }

    override suspend fun submit(expectedRevision: Long) {
        submissionMutex.withLock {
            val captured = state.value
            if (closed || captured.lifecycle == ComposerLifecycle.Closed) return@withLock
            if (captured.revision != expectedRevision) return@withLock
            if (captured.submission is ComposerSubmissionState.Submitting) return@withLock
            val text = captured.text.trim()
            if (text.isEmpty()) return@withLock
            val submitting = captured.copy(submission = ComposerSubmissionState.Submitting)
            if (!mutableState.compareAndSet(captured, submitting)) return@withLock

            val owner = captured.ownerId
            val content = listOf(ContentItem.InputText(text))
            // Command admission reads the fixed runtime, not a possibly delayed UI projection.
            val running = dependencies.runtime.running.value
            val operation = if (running) {
                ComposerOperation.Steer
            } else {
                ComposerOperation.Submit
            }
            try {
                dependencies.submit.submit(owner, content)
                clearAccepted(owner, captured.revision)
            } catch (cancelled: CancellationException) {
                restoreAfterCancellation(owner, captured.revision)
                throw cancelled
            } catch (failure: Throwable) {
                val summary = ComposerFailure(
                    operation = operation,
                    message = failure.message.takeUnless { it.isNullOrBlank() }
                        ?: failure.toString().ifBlank { "Composer command failed." },
                )
                retainFailure(owner, captured.revision, summary)
                dependencies.failures.report(owner, summary)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        observations.forEach(Job::cancel)
        observations.clear()
        mutableState.value = mutableState.value.copy(
            lifecycle = ComposerLifecycle.Closed,
            submission = ComposerSubmissionState.Editing,
        )
    }

    private fun clearAccepted(owner: ComposerOwnerId, revision: Long): Boolean {
        while (true) {
            val current = mutableState.value
            if (
                current.lifecycle == ComposerLifecycle.Closed ||
                current.ownerId != owner ||
                current.revision != revision ||
                current.submission !is ComposerSubmissionState.Submitting
            ) {
                return false
            }
            val cleared = current.copy(
                text = "",
                revision = current.nextRevision(),
                cursorOffset = 0,
                submission = ComposerSubmissionState.Editing,
            )
            if (mutableState.compareAndSet(current, cleared)) return true
        }
    }

    private fun restoreAfterCancellation(owner: ComposerOwnerId, revision: Long) {
        while (true) {
            val current = mutableState.value
            if (
                current.lifecycle == ComposerLifecycle.Closed ||
                current.ownerId != owner ||
                current.revision != revision ||
                current.submission !is ComposerSubmissionState.Submitting
            ) {
                return
            }
            if (
                mutableState.compareAndSet(
                    current,
                    current.copy(submission = ComposerSubmissionState.Editing),
                )
            ) {
                return
            }
        }
    }

    private fun retainFailure(
        owner: ComposerOwnerId,
        revision: Long,
        failure: ComposerFailure,
    ) {
        while (true) {
            val current = mutableState.value
            if (
                current.lifecycle == ComposerLifecycle.Closed ||
                current.ownerId != owner ||
                current.revision != revision ||
                current.submission !is ComposerSubmissionState.Submitting
            ) {
                return
            }
            val failed = current.copy(
                revision = current.nextRevision(),
                submission = ComposerSubmissionState.Failed(failure),
            )
            if (mutableState.compareAndSet(current, failed)) return
        }
    }

    private fun updateProjection(transform: (ComposerState) -> ComposerState) {
        if (closed) return
        while (true) {
            val current = mutableState.value
            if (current.lifecycle == ComposerLifecycle.Closed) return
            val updated = transform(current)
            if (mutableState.compareAndSet(current, updated)) return
        }
    }
}

private fun ComposerState.nextRevision(): Long {
    check(revision < Long.MAX_VALUE) { "Composer revisions are exhausted." }
    return revision + 1
}
