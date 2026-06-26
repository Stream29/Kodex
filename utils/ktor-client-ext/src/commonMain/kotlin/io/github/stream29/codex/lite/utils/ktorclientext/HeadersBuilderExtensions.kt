package io.github.stream29.codex.lite.utils.ktorclientext

import io.ktor.http.HeadersBuilder

/**
 * Adds, replaces, or removes a header value.
 *
 * @param value header value to set; `null` means the header should be absent.
 */
public operator fun HeadersBuilder.set(name: String, value: String?): Unit {
    if (value == null) {
        remove(name)
    } else {
        this[name] = value
    }
}

/**
 * Adds or replaces single-value headers from [values].
 */
public fun HeadersBuilder.addAll(values: Map<String, String>): Unit {
    values.forEach { (name, value) ->
        this[name] = value
    }
}
