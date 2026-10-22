package io.github.stream29.kodex.app.migration

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

/**
 * The application version used to select Kodex Home migrations.
 *
 * This value is initialized lazily from the version generated for the
 * application build and remains stable for the lifetime of this value.
 */
public expect val CurrentKodexApplicationVersion: MigrationVersion

/**
 * Prepares [home] for this application and returns a [KodexHomeHandle] owned by
 * this scope.
 *
 * Preparation validates the persisted version and, for an unversioned Home,
 * validates its existing Session layout before registering the supported
 * baseline. It applies only migrations in the existing registry whose targets
 * are newer than the stored version and no newer than
 * [CurrentKodexApplicationVersion]. A stored version newer than this
 * application, an invalid version, or an invalid unversioned layout is
 * rejected. The generated application version is not written as a checkpoint
 * unless it is itself a migration target; an already-versioned Home with no
 * applicable migrations retains its version without a rewrite or Session
 * layout scan.
 *
 * Preparation first obtains a shared read lease. If initialization or a
 * migration is needed, it releases that lease, obtains an exclusive write
 * lease, and reacquires a shared read lease before returning. The returned
 * handle retains that shared lease. Its lifetime is bound to this scope's
 * owner [kotlinx.coroutines.Job]: owner cancellation releases the lease, and
 * owner completion waits for cleanup. Keep the owner alive while using Home
 * data and close the returned handle after its users have finished.
 *
 * [onMigrationStarted] is invoked immediately before each applicable
 * migration action. If the callback throws, preparation propagates that
 * failure and does not run that migration action. Cancellation propagates and
 * completed filesystem changes are not rolled back. If preparation fails or
 * is cancelled after acquiring a lease but before delivering its handle,
 * cleanup of that undelivered lease is awaited; a distinct cleanup failure is
 * suppressed on the primary failure. A failure cleaning up a temporary lease
 * after otherwise successful preparation is reported.
 *
 * A validated unversioned baseline and each completed migration target are
 * recorded as checkpoints. A failed migration does not advance its checkpoint,
 * so preparation can resume from the last completed target.
 *
 * @param home Home directory to validate and prepare; it need not be the
 * default user Home.
 * @param fileSystem Filesystem used for Home reads, writes and leases.
 * @param onMigrationStarted Callback invoked before each applicable migration
 * action, with its source and target versions.
 * @return A handle retaining a shared read lease for the prepared Home.
 * @throws KodexHomeVersionException If `version.json` is non-regular,
 * unreadable, malformed or non-canonical; if the stored version is newer than
 * this application's version; or if the final prepared version is missing or
 * still requires migration.
 * @throws KodexHomeLayoutException If baseline validation rejects the Session
 * root, visible Session entries, required timelines or latest pointers of an
 * unversioned Home.
 * @throws KodexHomeInUseException If initialization or a migration cannot
 * acquire exclusive access because another instance holds a conflicting Home
 * lease.
 * @throws IllegalStateException If the migration registry contains duplicate
 * or non-increasing targets.
 * @throws IllegalArgumentException If this scope has no owner
 * [kotlinx.coroutines.Job].
 * @throws CancellationException If preparation or its coroutine operations are
 * cancelled.
 * @throws Throwable If a filesystem operation, lease operation, migration
 * action, [onMigrationStarted] callback, or successful-path temporary lease
 * cleanup fails; preparation cleanup failures are suppressed on the primary
 * failure where applicable.
 */
public expect suspend fun CoroutineScope.prepareKodexHome(
    home: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
    onMigrationStarted: (fromVersion: MigrationVersion, toVersion: MigrationVersion) -> Unit =
        { _, _ -> },
): KodexHomeHandle
