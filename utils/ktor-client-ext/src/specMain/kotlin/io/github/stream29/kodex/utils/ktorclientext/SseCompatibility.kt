package io.github.stream29.kodex.utils.ktorclientext

import io.ktor.client.plugins.api.ClientPlugin

/**
 * Supplies the SSE response content type when an explicitly marked server
 * response omits it.
 *
 * Ktor's official SSE plugin still owns request handling, response validation,
 * session lifecycle, and event parsing.
 */
public expect val SseCompatibility: ClientPlugin<Unit>
