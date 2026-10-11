package io.github.stream29.kodex.app.migration

/**
 * Home initialization or data migration cannot obtain exclusive access.
 *
 * The message identifies initialization or applicable data migration targets
 * and explains how to retry. The underlying lease conflict is retained
 * as [cause], with its lock paths included in the message.
 */
public class KodexHomeInUseException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)
