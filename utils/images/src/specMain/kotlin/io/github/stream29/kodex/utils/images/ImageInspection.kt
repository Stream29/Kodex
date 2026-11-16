package io.github.stream29.kodex.utils.images

/**
 * Detects the image MIME type from common file signatures.
 *
 * @return The detected MIME type, or `null` when this byte array does not match one of
 * the prompt-image formats this module recognizes.
 */
public expect fun ByteArray.detectImageMimeType(): ImageMimeType?

/**
 * Returns true when Codex can safely pass source bytes through byte-for-byte.
 */
public expect val ImageMimeType.canPreserveSourceBytes: Boolean

/**
 * Detects image format and dimensions from common image container headers.
 * This inspects headers only; it does not certify that a codec can decode the whole image.
 *
 * @return The detected image info, or `null` when this byte array does not
 * match a supported prompt-image container signature.
 * @throws InvalidImageException if a recognized header is truncated, lacks required
 * size fields, or declares dimensions outside the positive Int range.
 */
public expect fun ByteArray.detectImageInfo(): ImageInfo?

/**
 * Detects image format and dimensions or fails when the bytes are unsupported.
 *
 * @throws UnsupportedImageFormatException if no supported container signature matches.
 * @throws InvalidImageException if a recognized container has invalid dimension headers.
 */
public expect fun ByteArray.requireImageInfo(): ImageInfo
