@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.utils.oauthloopback

import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.server.application.ApplicationModulesLoaded
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStarting
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.logging.LogLevel
import io.ktor.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

internal class CioLoopbackEngine(
    scope: CoroutineScope,
    private val url: Url,
    private val claim: suspend (Parameters, String) -> (() -> Unit)?,
    bindPort: Int,
) : LoopbackEngine {
    private val config = serverConfig(applicationEnvironment { log = SilentCallbackLogger }) {
        parentCoroutineContext = scope.coroutineContext
        developmentMode = false
    }
    private val server = embeddedServer(CIO, config) {
        reuseAddress = false
        connector {
            host = "127.0.0.1"
            port = bindPort
        }
    }

    override suspend fun start(): Int {
        // Native EmbeddedServer.startSuspend installs process-global SIGINT/SIGTERM hooks.
        // Own only this engine; reproduce its application setup without those hooks.
        server.monitor.raise(ApplicationStarting, server.application)
        server.application.routing {
            get(url.encodedPath) {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.response.headers.append("Referrer-Policy", "no-referrer")
                val completion = try {
                    if (acceptsCallbackAuthority(
                        url, call.request.local.localPort,
                        call.request.headers.getAll(HttpHeaders.Host), call.request.uri.substringBefore('?'),
                    )) claim(call.request.queryParameters, call.request.uri.substringAfter('?', "")) else null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (completion != null) {
                    val requestJob = requireNotNull(currentCoroutineContext()[Job]).parent
                        ?: error("Callback request has no owner.")
                    requestJob.invokeOnCompletion { completion() }
                }
                call.respondText(
                    if (completion != null) "Response received. Return to Kodex for the result." else "Invalid sign-in response.",
                    status = if (completion != null) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                )
            }
        }
        server.monitor.raise(ApplicationModulesLoaded, server.application)
        server.monitor.raise(ApplicationStarted, server.application)
        server.engine.startSuspend()
        return server.engine.resolvedConnectors().single().port
    }

    override suspend fun stop() {
        server.stopSuspend()
    }
}

internal fun acceptsCallbackAuthority(url: Url, port: Int, hosts: List<String>?, path: String): Boolean {
    val host = hosts?.singleOrNull() ?: return false
    val expected = "${url.host}:$port"
    return path == url.encodedPath && (host == expected || port == 80 && host == url.host)
}

// Ktor's default failure logger includes the request URI and exception message.
// This isolated callback server reports sanitized lifecycle failures through its handle instead.
private object SilentCallbackLogger : Logger {
    override val level: LogLevel = LogLevel.ERROR
    override fun error(message: String) {}
    override fun error(message: String, cause: Throwable) {}
    override fun warn(message: String) {}
    override fun warn(message: String, cause: Throwable) {}
    override fun info(message: String) {}
    override fun info(message: String, cause: Throwable) {}
    override fun debug(message: String) {}
    override fun debug(message: String, cause: Throwable) {}
    override fun trace(message: String) {}
    override fun trace(message: String, cause: Throwable) {}
}
