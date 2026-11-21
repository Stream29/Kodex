package io.github.stream29.kodex.app.migration

/**
 * Home preflight or preparation cannot accept the persisted format.
 *
 * This includes legacy or unknown generations, unreadable or invalid checkpoints,
 * a newer checkpoint, an incompatible database, and unsupported initialization
 * states. Inspection failures retain their [cause] when available. Migration
 * action failures propagate separately.
 */
public open class KodexHomeVersionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Home preflight or preparation found an unexpected entry or type in an
 * unprepared Home or its startup infrastructure.
 *
 * An unprepared Home may contain only initialization artifacts and known
 * process infrastructure. Preparation does not remove arbitrary entries to
 * make such a Home eligible.
 */
public class KodexHomeLayoutException(
    message: String,
    cause: Throwable? = null,
) : KodexHomeVersionException(message, cause)
