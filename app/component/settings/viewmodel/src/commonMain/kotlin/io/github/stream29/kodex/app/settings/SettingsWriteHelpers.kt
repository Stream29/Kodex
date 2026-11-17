package io.github.stream29.kodex.app.settings

import kotlinx.coroutines.CancellationException

internal inline fun admitBooleanWrite(
    reportFailure: (Throwable) -> Unit,
    rejectedMessage: String,
    write: () -> Boolean,
) {
    try {
        if (!write()) {
            reportFailure(IllegalStateException(rejectedMessage))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        reportFailure(failure)
    }
}
