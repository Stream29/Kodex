package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

public class Migration(
    public val toVersion: MigrationVersion,
    public val action: suspend (
        home: Path,
        fileSystem: CoroutineFileSystem,
    ) -> Unit,
)
