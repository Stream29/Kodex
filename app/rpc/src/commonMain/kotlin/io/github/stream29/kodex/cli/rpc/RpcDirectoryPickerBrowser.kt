package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerBrowser
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerFailure
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerListing
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerLoadResult
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerValidationResult
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.DirectoryListResult
import io.github.stream29.kodex.rpc.models.DirectoryQuery
import io.github.stream29.kodex.rpc.models.DirectoryQueryFailure
import io.github.stream29.kodex.rpc.models.DirectoryValidationResult
import kotlinx.io.files.Path

/** No state machine or OS parsing: names, parents and ordering come from the captured backend. */
internal class RpcDirectoryPickerBrowser(
    private val rpc: GlobalRpc,
    private val launchBaseDirectory: String,
) : DirectoryPickerBrowser {
    override suspend fun validate(directory: Path): DirectoryPickerValidationResult {
        val result = rpc.validateDirectory(DirectoryQuery(directory.toString(), launchBaseDirectory))
        return try {
            when (result) {
                is DirectoryValidationResult.Directory ->
                    DirectoryPickerValidationResult.Success(result.path.asBackendPath())
                is DirectoryValidationResult.Failure -> DirectoryPickerValidationResult.Failure(
                    result.reason.toPickerFailure(directory),
                    result.parentDirectory?.asBackendPath(),
                )
            }
        } catch (_: UnrepresentableBackendPath) {
            DirectoryPickerValidationResult.Failure(unsupportedPathSpelling())
        }
    }

    override suspend fun load(directory: Path): DirectoryPickerLoadResult {
        val result = rpc.listDirectories(DirectoryQuery(directory.toString(), launchBaseDirectory))
        return try {
            when (result) {
                is DirectoryListResult.Directories -> {
                    val children = result.children.map { it.path.asBackendPath() }
                    DirectoryPickerLoadResult.Success(
                        DirectoryPickerListing(
                            directory = result.directory.asBackendPath(),
                            children = children,
                            parentDirectory = result.parentDirectory?.asBackendPath(),
                            childNames = children.zip(result.children.map { it.name }).toMap(),
                        ),
                    )
                }
                is DirectoryListResult.Failure -> DirectoryPickerLoadResult.Failure(
                    result.reason.toPickerFailure(directory),
                    result.parentDirectory?.asBackendPath(),
                )
            }
        } catch (_: UnrepresentableBackendPath) {
            DirectoryPickerLoadResult.Failure(unsupportedPathSpelling())
        }
    }
}

// Path is only the existing component carrier, never the authority for backend spelling.
private fun String.asBackendPath(): Path {
    val path = try {
        Path(this)
    } catch (_: IllegalArgumentException) {
        throw UnrepresentableBackendPath()
    }
    if (path.toString() != this) throw UnrepresentableBackendPath()
    return path
}

private class UnrepresentableBackendPath : Exception()
private fun unsupportedPathSpelling(): DirectoryPickerFailure =
    DirectoryPickerFailure.FileSystem("Backend path spelling cannot be represented by this frontend.")

private fun DirectoryQueryFailure.toPickerFailure(requested: Path): DirectoryPickerFailure = when (this) {
    DirectoryQueryFailure.HomeDirectoryUnavailable -> DirectoryPickerFailure.HomeDirectoryUnavailable
    DirectoryQueryFailure.NotDirectory -> DirectoryPickerFailure.NotDirectory(requested)
    DirectoryQueryFailure.InvalidPath -> DirectoryPickerFailure.FileSystem("Invalid directory path.")
    DirectoryQueryFailure.InvalidLaunchBase -> DirectoryPickerFailure.FileSystem("Invalid launch directory.")
    DirectoryQueryFailure.FileSystem -> DirectoryPickerFailure.FileSystem("Backend filesystem query failed.")
    DirectoryQueryFailure.LimitExceeded -> DirectoryPickerFailure.FileSystem("Directory query limit exceeded.")
}
