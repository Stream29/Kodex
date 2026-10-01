package io.github.stream29.kodex.app.pathpicker

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerEffect
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerBrowser
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerDependencies
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadResult
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerState
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerValidationResult
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModelFactory
import io.github.stream29.kodex.app.pathpicker.contract.currentDirectory
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

internal class DirectoryPickerViewModelImpl(
    initialDirectory: Path,
    private val dependencies: DirectoryPickerDependencies,
    parentScope: CoroutineScope,
) : DirectoryPickerViewModel {
    internal constructor(
        initialDirectory: Path,
        browser: DirectoryPickerBrowser,
        parentScope: CoroutineScope,
    ) : this(
        initialDirectory = initialDirectory,
        dependencies = directoryPickerDependencies(browser),
        parentScope = parentScope,
    )

    private val browser: DirectoryPickerBrowser = dependencies.browser
    private val scope = parentScope.supervisorChildScope()
    private val mutableState = MutableStateFlow(
        DirectoryPickerState(
            loadState = DirectoryPickerLoadState.Loading(
                requestId = InitialRequestId,
                requestedDirectory = initialDirectory,
            ),
        ),
    )
    private val effectChannel = Channel<DirectoryPickerEffect>(Channel.BUFFERED)
    private val confirmingRequestId = MutableStateFlow<Long?>(null)

    override val state: StateFlow<DirectoryPickerState> = mutableState.asStateFlow()
    override val effects: Flow<DirectoryPickerEffect> = effectChannel.receiveAsFlow()

    init {
        launchLoad(checkNotNull(mutableState.value.loadState as? DirectoryPickerLoadState.Loading))
    }

    override fun navigateTo(directory: Path) {
        request(
            directory = directory,
            expectedRequestId = null,
            clearFilter = true,
        )
    }

    override fun navigateUp() {
        val current = mutableState.value
        if (current.loadState is DirectoryPickerLoadState.Loading) return
        val parent = current.currentDirectory.parent ?: return
        request(
            directory = parent,
            expectedRequestId = current.loadState.requestId,
            clearFilter = true,
        )
    }

    override fun updateFilter(query: String) {
        if (!scope.isActive) return
        mutableState.update { current ->
            if (current.filterQuery == query) current else current.copy(filterQuery = query)
        }
    }

    override fun clearFilter() {
        updateFilter("")
    }

    override fun retry() {
        val failed = mutableState.value.loadState as? DirectoryPickerLoadState.Failed ?: return
        request(
            directory = failed.requestedDirectory,
            expectedRequestId = failed.requestId,
            clearFilter = false,
        )
    }

    override fun confirm() {
        if (!scope.isActive) return
        val current = mutableState.value.loadState
        while (scope.isActive) {
            val confirming = confirmingRequestId.value
            if (confirming == current.requestId) return
            if (confirmingRequestId.compareAndSet(confirming, current.requestId)) break
        }
        if (!scope.isActive) return
        scope.launch {
            try {
                when (val validated = browser.validate(current.requestedDirectory)) {
                    is DirectoryPickerValidationResult.Success -> {
                        if (mutableState.value.loadState.requestId == current.requestId) {
                            effectChannel.trySend(
                                DirectoryPickerEffect.DirectorySelected(validated.directory),
                            )
                        }
                    }

                    is DirectoryPickerValidationResult.Failure -> mutableState.update { latest ->
                        if (latest.loadState.requestId != current.requestId) latest
                        else latest.copy(
                            loadState = DirectoryPickerLoadState.Failed(
                                requestId = current.requestId,
                                requestedDirectory = current.requestedDirectory,
                                failure = validated.failure,
                            ),
                        )
                    }
                }
            } finally {
                confirmingRequestId.compareAndSet(current.requestId, null)
            }
        }
    }

    override fun close() {
        effectChannel.close()
        scope.cancel()
    }

    private fun request(
        directory: Path,
        expectedRequestId: Long?,
        clearFilter: Boolean,
    ) {
        while (scope.isActive) {
            val current = mutableState.value
            if (expectedRequestId != null && current.loadState.requestId != expectedRequestId) return
            check(current.loadState.requestId < Long.MAX_VALUE) {
                "Directory-picker request ids are exhausted."
            }
            val loading = DirectoryPickerLoadState.Loading(
                requestId = current.loadState.requestId + 1,
                requestedDirectory = directory,
            )
            val updated = current.copy(
                filterQuery = if (clearFilter) "" else current.filterQuery,
                loadState = loading,
            )
            if (mutableState.compareAndSet(current, updated)) {
                launchLoad(loading)
                return
            }
        }
    }

    private fun launchLoad(request: DirectoryPickerLoadState.Loading) {
        scope.launch {
            val completed = when (val result = browser.load(request.requestedDirectory)) {
                is DirectoryPickerLoadResult.Success ->
                    DirectoryPickerLoadState.Ready(
                        requestId = request.requestId,
                        requestedDirectory = request.requestedDirectory,
                        directory = result.listing.directory,
                        children = result.listing.children,
                    )

                is DirectoryPickerLoadResult.Failure ->
                    DirectoryPickerLoadState.Failed(
                        requestId = request.requestId,
                        requestedDirectory = request.requestedDirectory,
                        failure = result.failure,
                    )
            }
            mutableState.update { current ->
                if (current.loadState.requestId == request.requestId) {
                    current.copy(loadState = completed)
                } else {
                    current
                }
            }
        }
    }

    private companion object {
        const val InitialRequestId: Long = 1
    }
}

private fun directoryPickerDependencies(browser: DirectoryPickerBrowser): DirectoryPickerDependencies =
    object : DirectoryPickerDependencies {
        override val browser: DirectoryPickerBrowser = browser
    }

/** Creates one short-lived picker ViewModel backed by the system filesystem. */
public fun createDirectoryPickerViewModel(
    initialDirectory: Path,
    ownerScope: CoroutineScope,
): DirectoryPickerViewModel =
    DefaultDirectoryPickerViewModelFactory.create(
        initialDirectory = initialDirectory,
        dependencies = directoryPickerDependencies(SystemDirectoryPickerBrowser()),
        ownerScope = ownerScope,
    )

/** Creates a picker using only the component's declared dependency ports. */
public fun createDirectoryPickerViewModel(
    initialDirectory: Path,
    dependencies: DirectoryPickerDependencies,
    ownerScope: CoroutineScope,
): DirectoryPickerViewModel =
    DefaultDirectoryPickerViewModelFactory.create(initialDirectory, dependencies, ownerScope)

public object DefaultDirectoryPickerViewModelFactory : DirectoryPickerViewModelFactory {
    override fun create(
        initialDirectory: Path,
        dependencies: DirectoryPickerDependencies,
        ownerScope: CoroutineScope,
    ): DirectoryPickerViewModel =
        DirectoryPickerViewModelImpl(initialDirectory, dependencies, ownerScope)
}
