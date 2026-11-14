package io.github.stream29.kodex.tool.unifiedexec

/** Minimum command yield in milliseconds on non-Windows hosts. */
public const val UnifiedExecMinimumYieldTimeMillis: Long = 250L

/** Maximum command yield in milliseconds (10 minutes). */
public const val UnifiedExecMaximumYieldTimeMillis: Long = 600_000L

/** Maximum retained process-output byte budget; a truncation marker may add bytes. */
public const val UnifiedExecMaximumOutputByteCount: Int = 1_024 * 1_024

/** Duration (10 minutes) a completed session remains in `activeSessions` for cursor reads before eviction. */
public const val UnifiedExecCompletedSessionRetentionMillis: Long = 600_000L
