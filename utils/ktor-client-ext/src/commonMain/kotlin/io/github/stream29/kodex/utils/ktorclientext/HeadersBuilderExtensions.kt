package io.github.stream29.kodex.utils.ktorclientext

import io.ktor.http.HeadersBuilder

public actual operator fun HeadersBuilder.set(name: String, value: String?): Unit {
    if (value == null) {
        remove(name)
    } else {
        this[name] = value
    }
}

public actual fun HeadersBuilder.addAll(values: Map<String, String>): Unit {
    values.forEach { (name, value) ->
        this[name] = value
    }
}
