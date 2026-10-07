package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import kotlinx.io.files.Path

/**
 * One ordinary, local-filesystem Home migration, selected by [toVersion].
 *
 * The Home preparation factory executes applicable entries in ascending version
 * order under its exclusive lease. It records [toVersion] only after [action]
 * returns normally. An action failure or cancellation is propagated; filesystem
 * changes already made are not rolled back. A later preparation calls the
 * applicable action again from its beginning, not from a saved cursor.
 *
 * @property toVersion Target application version; registry targets must be unique
 * and strictly increasing. Entries above the running version are not executed.
 * @property action Receives the Home and its filesystem after prior applicable
 * entries have completed. Each historical action owns its compatibility and
 * reentry behavior; the entry itself supplies no transaction or recovery.
 */
public class Migration(
    public val toVersion: MigrationVersion,
    public val action: suspend (
        home: Path,
        fileSystem: CoroutineFileSystem,
    ) -> Unit,
)
