package io.github.stream29.kodex.cli.auth

import io.github.stream29.kodex.openai.OpenAiAuthorizationCodeExchange
import io.github.stream29.kodex.openai.OpenAiLoginAuthorization
import io.github.stream29.kodex.openai.OpenAiSubscriptionTokens
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.generateNonceBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Local browser-login attempt backed by a short-lived loopback callback listener. */
internal class LocalKodexLoginAttempt private constructor(
    private val callbackServer: LocalLoginCallbackServer,
    private val loginClient: OpenAiLoginClient,
    private val redirectUri: String,
    private val codeVerifier: String,
    private val persistTokens: suspend (OpenAiSubscriptionTokens) -> Unit,
    private val onFinished: suspend (LocalKodexLoginAttempt) -> Unit,
    override val authorizationUrl: String,
) : KodexAuthLoginAttempt {
    private val finishMutex = Mutex()
    private var finished = false

    override suspend fun awaitCompletion() {
        try {
            val authorizationCode = callbackServer.awaitAuthorizationCode()
            val tokens = loginClient.exchangeAuthorizationCode(
                OpenAiAuthorizationCodeExchange(
                    authorizationCode = authorizationCode,
                    redirectUri = redirectUri,
                    codeVerifier = codeVerifier,
                ),
            )
            persistTokens(tokens)
        } finally {
            withContext(NonCancellable) { finish() }
        }
    }

    override fun cancel() {
        callbackServer.cancel()
    }

    private suspend fun finish() {
        finishMutex.withLock {
            if (finished) return
            finished = true
            try {
                callbackServer.close()
            } finally {
                onFinished(this)
            }
        }
    }

    internal companion object {
        suspend fun start(
            scope: CoroutineScope,
            loginClient: OpenAiLoginClient,
            persistTokens: suspend (OpenAiSubscriptionTokens) -> Unit,
            onFinished: suspend (LocalKodexLoginAttempt) -> Unit,
            callbackPorts: List<Int> = DefaultCallbackPorts,
        ): LocalKodexLoginAttempt {
            val codeVerifier = generateNonceBlocking(PkceCodeVerifierLength)
            val state = generateNonceBlocking(LoginStateLength)
            val callbackServer = LocalLoginCallbackServer.start(
                scope = scope,
                expectedState = state,
                candidatePorts = callbackPorts,
            )
            val redirectUri = callbackServer.redirectUri
            return try {
                val authorizationUrl = loginClient.authorizationUrl(
                    OpenAiLoginAuthorization(
                        redirectUri = redirectUri,
                        codeChallenge = pkceCodeChallenge(codeVerifier),
                        state = state,
                    ),
                )
                LocalKodexLoginAttempt(
                    callbackServer = callbackServer,
                    loginClient = loginClient,
                    redirectUri = redirectUri,
                    codeVerifier = codeVerifier,
                    persistTokens = persistTokens,
                    onFinished = onFinished,
                    authorizationUrl = authorizationUrl,
                )
            } catch (failure: Throwable) {
                callbackServer.close()
                throw failure
            }
        }
    }
}

/** A loopback callback endpoint restricted to the Codex OAuth redirect URI ports. */
private class LocalLoginCallbackServer private constructor(
    private val server: EmbeddedServer<*, *>,
    private val port: Int,
    private val authorizationCode: CompletableDeferred<String>,
) {
    val redirectUri: String
        get() = "http://localhost:$port/auth/callback"

    suspend fun awaitAuthorizationCode(): String = authorizationCode.await()

    fun cancel() {
        authorizationCode.cancel()
        server.stop()
    }

    suspend fun close() {
        server.stopSuspend()
    }

    internal companion object {
        suspend fun start(
            scope: CoroutineScope,
            expectedState: String,
            candidatePorts: List<Int>,
        ): LocalLoginCallbackServer {
            require(candidatePorts.isNotEmpty()) { "candidatePorts must not be empty." }
            var lastFailure: Throwable? = null
            for (candidatePort in candidatePorts) {
                val authorizationCode = CompletableDeferred<String>()
                val server = scope.embeddedServer(CIO, port = candidatePort, host = LoopbackHost) {
                    routing {
                        get("/auth/callback") {
                            val response = handleLoginCallback(
                                expectedState = expectedState,
                                completion = authorizationCode,
                                state = call.request.queryParameters["state"],
                                authorizationCode = call.request.queryParameters["code"],
                                error = call.request.queryParameters["error"],
                            )
                            call.respondText(response.body, status = response.status)
                        }
                    }
                }
                try {
                    server.startSuspend()
                    val resolvedPort = server.engine.resolvedConnectors().single().port
                    return LocalLoginCallbackServer(
                        server = server,
                        port = resolvedPort,
                        authorizationCode = authorizationCode,
                    )
                } catch (failure: Throwable) {
                    server.stop()
                    lastFailure = failure
                }
            }
            throw LocalLoginException(
                "Unable to start a local sign-in listener on the required callback ports.",
                lastFailure,
            )
        }
    }
}

private fun handleLoginCallback(
    expectedState: String,
    completion: CompletableDeferred<String>,
    state: String?,
    authorizationCode: String?,
    error: String?,
): CallbackResponse = when {
    state != expectedState -> CallbackResponse(
        status = HttpStatusCode.BadRequest,
        body = "The sign-in response could not be verified. Return to Kodex and try again.",
    )

    !error.isNullOrBlank() -> {
        completion.completeExceptionally(LocalLoginException("The browser sign-in was not completed."))
        CallbackResponse(
            status = HttpStatusCode.BadRequest,
            body = "Sign-in was not completed. Return to Kodex to try again.",
        )
    }

    authorizationCode.isNullOrBlank() -> {
        completion.completeExceptionally(
            LocalLoginException("The browser sign-in returned no authorization code."),
        )
        CallbackResponse(
            status = HttpStatusCode.BadRequest,
            body = "The sign-in response was incomplete. Return to Kodex and try again.",
        )
    }

    else -> {
        completion.complete(authorizationCode)
        CallbackResponse(
            status = HttpStatusCode.OK,
            body = "Sign-in complete. You can return to Kodex.",
        )
    }
}

private data class CallbackResponse(
    val status: HttpStatusCode,
    val body: String,
)

private class LocalLoginException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

@OptIn(ExperimentalEncodingApi::class)
internal fun pkceCodeChallenge(codeVerifier: String): String =
    Base64.UrlSafe.encode(sha256(codeVerifier.encodeToByteArray())).trimEnd('=')

private fun sha256(input: ByteArray): ByteArray {
    val bitLength = input.size.toLong() * 8L
    val paddingLength = ((56 - (input.size + 1) % Sha256BlockSize) + Sha256BlockSize) % Sha256BlockSize
    val padded = ByteArray(input.size + 1 + paddingLength + Sha256LengthFieldSize)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (index in 0 until Sha256LengthFieldSize) {
        padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
    }

    var a = 0x6a09e667
    var b = 0xbb67ae85.toInt()
    var c = 0x3c6ef372
    var d = 0xa54ff53a.toInt()
    var e = 0x510e527f
    var f = 0x9b05688c.toInt()
    var g = 0x1f83d9ab
    var h = 0x5be0cd19
    val words = IntArray(Sha256WordCount)

    padded.asList().chunked(Sha256BlockSize).forEach { block ->
        for (index in 0 until 16) {
            val offset = index * 4
            words[index] = ((block[offset].toInt() and 0xff) shl 24) or
                ((block[offset + 1].toInt() and 0xff) shl 16) or
                ((block[offset + 2].toInt() and 0xff) shl 8) or
                (block[offset + 3].toInt() and 0xff)
        }
        for (index in 16 until Sha256WordCount) {
            val smallSigma0 = words[index - 15].rotateRight(7) xor
                words[index - 15].rotateRight(18) xor (words[index - 15] ushr 3)
            val smallSigma1 = words[index - 2].rotateRight(17) xor
                words[index - 2].rotateRight(19) xor (words[index - 2] ushr 10)
            words[index] = words[index - 16] + smallSigma0 + words[index - 7] + smallSigma1
        }

        var workingA = a
        var workingB = b
        var workingC = c
        var workingD = d
        var workingE = e
        var workingF = f
        var workingG = g
        var workingH = h
        for (index in 0 until Sha256WordCount) {
            val bigSigma1 = workingE.rotateRight(6) xor workingE.rotateRight(11) xor workingE.rotateRight(25)
            val choose = (workingE and workingF) xor (workingE.inv() and workingG)
            val temporary1 = workingH + bigSigma1 + choose + Sha256RoundConstants[index] + words[index]
            val bigSigma0 = workingA.rotateRight(2) xor workingA.rotateRight(13) xor workingA.rotateRight(22)
            val majority = (workingA and workingB) xor (workingA and workingC) xor (workingB and workingC)
            val temporary2 = bigSigma0 + majority

            workingH = workingG
            workingG = workingF
            workingF = workingE
            workingE = workingD + temporary1
            workingD = workingC
            workingC = workingB
            workingB = workingA
            workingA = temporary1 + temporary2
        }

        a += workingA
        b += workingB
        c += workingC
        d += workingD
        e += workingE
        f += workingF
        g += workingG
        h += workingH
    }

    return intArrayOf(a, b, c, d, e, f, g, h).flatMap { value ->
        listOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }.toByteArray()
}

private fun Int.rotateRight(bitCount: Int): Int =
    (this ushr bitCount) or (this shl (Int.SIZE_BITS - bitCount))

private const val LoopbackHost: String = "127.0.0.1"
private const val LoginStateLength: Int = 64
private const val PkceCodeVerifierLength: Int = 96
private const val Sha256BlockSize: Int = 64
private const val Sha256LengthFieldSize: Int = 8
private const val Sha256WordCount: Int = 64

private val DefaultCallbackPorts: List<Int> = listOf(1455, 1457)

private val Sha256RoundConstants: IntArray = intArrayOf(
    0x428a2f98,
    0x71374491,
    0xb5c0fbcf.toInt(),
    0xe9b5dba5.toInt(),
    0x3956c25b,
    0x59f111f1,
    0x923f82a4.toInt(),
    0xab1c5ed5.toInt(),
    0xd807aa98.toInt(),
    0x12835b01,
    0x243185be,
    0x550c7dc3,
    0x72be5d74,
    0x80deb1fe.toInt(),
    0x9bdc06a7.toInt(),
    0xc19bf174.toInt(),
    0xe49b69c1.toInt(),
    0xefbe4786.toInt(),
    0x0fc19dc6,
    0x240ca1cc,
    0x2de92c6f,
    0x4a7484aa,
    0x5cb0a9dc,
    0x76f988da,
    0x983e5152.toInt(),
    0xa831c66d.toInt(),
    0xb00327c8.toInt(),
    0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(),
    0xd5a79147.toInt(),
    0x06ca6351,
    0x14292967,
    0x27b70a85,
    0x2e1b2138,
    0x4d2c6dfc,
    0x53380d13,
    0x650a7354,
    0x766a0abb,
    0x81c2c92e.toInt(),
    0x92722c85.toInt(),
    0xa2bfe8a1.toInt(),
    0xa81a664b.toInt(),
    0xc24b8b70.toInt(),
    0xc76c51a3.toInt(),
    0xd192e819.toInt(),
    0xd6990624.toInt(),
    0xf40e3585.toInt(),
    0x106aa070,
    0x19a4c116,
    0x1e376c08,
    0x2748774c,
    0x34b0bcb5,
    0x391c0cb3,
    0x4ed8aa4a,
    0x5b9cca4f,
    0x682e6ff3,
    0x748f82ee,
    0x78a5636f,
    0x84c87814.toInt(),
    0x8cc70208.toInt(),
    0x90befffa.toInt(),
    0xa4506ceb.toInt(),
    0xbef9a3f7.toInt(),
    0xc67178f2.toInt(),
)
