package io.github.stream29.kodex.app.migration

/**
 * Home preparation cannot accept the persisted version.
 *
 * The preparation factory throws this for a non-regular, unreadable or malformed
 * `version.json`, a non-canonical version, a version newer than the running
 * application, or a missing/changed version at its final post-preparation check.
 * Version read and parse failures retain their underlying [cause] when available.
 * Filesystem metadata failures and migration action failures are not universally
 * wrapped in this exception.
 */
public open class KodexHomeVersionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Home preparation rejected the Session layout of an unversioned Home.
 *
 * This includes a non-directory Sessions root, noncanonical visible entry names,
 * non-directory entries, missing/invalid required timeline layout, and invalid
 * or inconsistent latest pointers. Hidden entries and unknown user data are not
 * rewritten by baseline validation. Layout and pointer validation failures retain
 * their [cause] when available; cancellation is not wrapped.
 */
public class KodexHomeLayoutException(
    message: String,
    cause: Throwable? = null,
) : KodexHomeVersionException(message, cause)
