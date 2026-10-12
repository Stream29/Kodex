package io.github.stream29.kodex.tool.builder

/**
 * Result algebra shared by JSON tool adapters.
 *
 * The implementation module owns event construction and JSON decoding; this
 * type is the stable boundary for handlers that only need to report success
 * or a model-facing failure.
 */
public sealed interface JsonToolHandlerResult<out Output> {
    public data class Success<out Output>(
        public val value: Output,
        public val success: Boolean = true,
    ) : JsonToolHandlerResult<Output>

    public data class Failure(
        public val message: String,
    ) : JsonToolHandlerResult<Nothing>
}

public fun <Output> jsonToolSuccess(
    value: Output,
    success: Boolean = true,
): JsonToolHandlerResult<Output> =
    JsonToolHandlerResult.Success(value, success)

public fun jsonToolFailure(message: String): JsonToolHandlerResult<Nothing> =
    JsonToolHandlerResult.Failure(message)
