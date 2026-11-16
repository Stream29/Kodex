package io.github.stream29.kodex.utils.images

/**
 * Fits dimensions into a bounding square while preserving aspect ratio.
 *
 * @throws IllegalArgumentException if [maxDimension] is not positive.
 */
public expect fun ImageDimensions.fitWithinMaxDimension(maxDimension: Int): ImageDimensions
