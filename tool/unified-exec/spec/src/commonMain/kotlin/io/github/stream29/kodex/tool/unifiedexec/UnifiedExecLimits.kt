package io.github.stream29.kodex.tool.unifiedexec

/** Minimum command yield in milliseconds on non-Windows hosts. */
public const val UnifiedExecMinimumYieldTimeMillis: Long = 250L

/** Maximum command yield in milliseconds. */
public const val UnifiedExecMaximumYieldTimeMillis: Long = 30_000L

/** Minimum empty-poll yield in milliseconds. */
public const val UnifiedExecMinimumEmptyPollYieldTimeMillis: Long = 5_000L

/** Maximum empty-poll yield in milliseconds. */
public const val UnifiedExecMaximumEmptyPollYieldTimeMillis: Long = 300_000L

/** Maximum retained process-output byte budget; a truncation marker may add bytes. */
public const val UnifiedExecMaximumOutputByteCount: Int = 1_024 * 1_024
