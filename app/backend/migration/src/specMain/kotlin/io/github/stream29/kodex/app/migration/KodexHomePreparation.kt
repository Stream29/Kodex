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
 * Checks whether [home] is eligible for preparation without creating files,
 * acquiring leases, or opening SQLite. Accepts a fresh Home, a recognizable
 * interrupted initialization from this build version, or a supported ready
 * marker. A published database must carry the current Home generation signature
 * in its SQLite header. Use [prepareKodexHome] to validate the current schema
 * under a lease; this probe alone does not authorize opening Home data.
 *
 * @throws KodexHomeVersionException If the Home is incompatible or cannot be
 * validated by this probe. The error explains how to keep the existing Home
 * intact and select a different empty Home.
 */
public expect suspend fun preflightKodexHome(
    home: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Unit

/**
 * Prepares [home] for this application and returns a [KodexHomeHandle] owned by
 * this scope.
 *
 * Preparation first performs [preflightKodexHome], then rechecks under
 * each acquired lease. An absent marker is eligible for initialization only
 * when the Home contains no data and at most recognized startup infrastructure.
 * A fresh Home publishes an initializing marker, creates and atomically
 * publishes the current SQLite database, then publishes its ready marker at
 * [CurrentKodexApplicationVersion]. If interrupted after SQLite publication,
 * recovery validates that database and its emptiness without recreating it.
 * Already-ready Homes apply only same-generation migration targets newer than
 * their checkpoint and no newer than the running application. With no
 * applicable migrations they retain their checkpoint without rewriting it.
 * The ready schema must be valid before a handle is returned. If there are
 * applicable migrations, their actions own validation of their compatible
 * inputs; the latest schema is required once the last action completes.
 *
 * After preflight, preparation obtains a shared read lease. If initialization
 * or migration is needed, it releases that lease, obtains an exclusive write
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
 * Each completed migration target is recorded in an atomic checkpoint. A
 * failed migration does not advance its checkpoint, so preparation can resume
 * from the last completed target.
 *
 * @param home Home directory to validate and prepare; it need not be the
 * default user Home.
 * @param fileSystem Filesystem used for Home reads, writes and leases. The
 * SQLite database path must also be accessible to the local SQLite driver.
 * @param onMigrationStarted Callback invoked before each applicable migration
 * action, with its source and target versions.
 * @return A handle retaining a shared read lease for the prepared Home.
 * @throws KodexHomeVersionException If the persisted generation, version,
 * database, or initialization state is incompatible or corrupt.
 * @throws KodexHomeLayoutException If an unprepared Home contains entries
 * disallowed during initialization.
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
