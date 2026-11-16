package io.github.stream29.kodex.utils.oauthloopback

/**
 * Computes the RFC 7636 `S256` PKCE `code_challenge` (unpadded URL-safe Base64 of the UTF-8
 * SHA-256 digest) for [codeVerifier].
 */
public expect fun pkceS256CodeChallenge(codeVerifier: String): String

/** Computes the 32-byte FIPS 180-4 SHA-256 digest of [input]. */
public expect fun sha256(input: ByteArray): ByteArray
