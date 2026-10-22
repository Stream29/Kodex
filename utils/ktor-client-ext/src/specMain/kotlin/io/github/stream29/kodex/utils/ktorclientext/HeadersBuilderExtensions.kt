package io.github.stream29.kodex.utils.ktorclientext

import io.ktor.http.HeadersBuilder

/**
 * Adds, replaces, or removes a header value.
 *
 * @param value header value to set; `null` means the header should be absent.
 */
public expect operator fun HeadersBuilder.set(name: String, value: String?): Unit

/**
 * Adds or replaces single-value headers from [values].
 */
public expect fun HeadersBuilder.addAll(values: Map<String, String>): Unit
