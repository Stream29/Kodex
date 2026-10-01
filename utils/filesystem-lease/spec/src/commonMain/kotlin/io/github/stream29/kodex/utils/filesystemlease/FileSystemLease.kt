package io.github.stream29.kodex.utils.filesystemlease

import kotlinx.coroutines.CoroutineScope

public interface FileSystemLease : AutoCloseable, CoroutineScope

public class FileSystemLeaseInUseException(
    message: String,
) : IllegalStateException(message)
