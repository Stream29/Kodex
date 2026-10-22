package io.github.stream29.kodex.utils.images

/**
 * Fits dimensions into a bounding square while preserving aspect ratio.
 *
 * @throws IllegalArgumentException if [maxDimension] is not positive.
 */
public expect fun ImageDimensions.fitWithinMaxDimension(maxDimension: Int): ImageDimensions

/**
 * Computes the output dimensions for Codex prompt image patch and dimension limits.
 */
public expect fun ImageDimensions.fitPromptImageLimits(
    limits: PromptImageResizeLimits,
): ImageDimensions

/**
 * Returns true when dimensions already satisfy Codex prompt image limits.
 */
public expect fun ImageDimensions.fitsPromptImageLimits(
    limits: PromptImageResizeLimits,
): Boolean
