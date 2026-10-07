package io.github.stream29.kodex.utils.rpcexception

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

private fun nonLocalReturn(): Int {
    restoreRemoteException { return 7 }
}

val restoreRemoteExceptionTest by testSuite {
    test("success preserves the return value and invokes the block once") {
        val value = Any()
        var calls = 0
        assertSame(value, restoreRemoteException {
            calls++
            value
        })
        assertEquals(1, calls)
        assertNull(restoreRemoteException { null })
        assertEquals(Unit, restoreRemoteException {})
        assertEquals(7, nonLocalReturn())
    }

    test("default sealed type discriminator restores a concrete exception") {
        val source = NoMatchException()
        val restored = assertFailsWith<NoMatchException> {
            restoreRemoteException { throw Throwable(source.message) }
        }
        assertNotSame(source, restored)
        assertNull(restored.cause)
    }

    test("message serializes the closed hierarchy without encoding Throwable properties") {
        val source = NoMatchException()
        val message = source.message
        assertEquals(Json.encodeToString<RemoteException>(source), message)
        assertEquals(setOf("type"), Json.parseToJsonElement(message).jsonObject.keys)
        assertEquals(message, source.message)
        val restored = Json.decodeFromString<RemoteException>(message)
        assertEquals(message, restored.message)
    }

    test("a thrown known exception supplies its own decodable message") {
        val source = NoMatchException()
        val restored = assertFailsWith<NoMatchException> {
            restoreRemoteException { throw source }
        }
        assertEquals(source.message, restored.message)
    }

    test("inactive Session restores separately from an unmatched output flow") {
        val source = SessionNotActive()
        val restored = assertFailsWith<SessionNotActive> {
            restoreRemoteException { throw Throwable(source.message) }
        }
        assertNotSame(source, restored)
        assertNull(restored.cause)
        assertEquals(source.message, restored.message)
        assertEquals(setOf("type"), Json.parseToJsonElement(source.message).jsonObject.keys)
        assertEquals(Json.encodeToString<RemoteException>(source), source.message)
        assertFailsWith<NoMatchException> {
            restoreRemoteException { throw Throwable(NoMatchException().message) }
        }
    }

    test("Session inactivity ends upstream collection and runs its cleanup") {
        var released = false
        val values = mutableListOf<Int>()
        val upstream = flow {
            try {
                emit(1)
                yield()
                throw Throwable(SessionNotActive().message)
            } finally {
                released = true
            }
        }.catch { cause ->
            restoreRemoteException { throw cause }
        }
        assertFailsWith<SessionNotActive> { upstream.collect { values += it } }
        assertEquals(listOf(1), values)
        assertEquals(true, released)
    }

    test("missing persisted Session restores as its own fieldless exception") {
        val source = SessionNotFound()
        val restored = assertFailsWith<SessionNotFound> {
            restoreRemoteException { throw Throwable(source.message) }
        }
        assertNotSame(source, restored)
        assertNull(restored.cause)
        assertEquals(source.message, restored.message)
        assertEquals(Json.encodeToString<RemoteException>(source), source.message)
        val payload = Json.parseToJsonElement(source.message).jsonObject
        assertEquals(setOf("type"), payload.keys)
        assertEquals(
            "io.github.stream29.kodex.utils.rpcexception.SessionNotFound",
            payload.getValue("type").jsonPrimitive.content,
        )
        assertEquals(4, listOf(
            source.message,
            SessionNotActive().message,
            NoMatchException().message,
            CacheNonceMismatch().message,
        ).toSet().size)
    }

    test("missing Session restoration neither retries nor infers absence from diagnostic text") {
        var calls = 0
        assertFailsWith<SessionNotFound> {
            restoreRemoteException {
                calls++
                yield()
                throw SessionNotFound()
            }
        }
        assertEquals(1, calls)
        val diagnostic = Throwable("SessionNotFound")
        assertSame(diagnostic, assertFailsWith<Throwable> {
            restoreRemoteException { throw diagnostic }
        })
    }

    test("cache nonce mismatch restores without adding metadata to the payload") {
        val source = CacheNonceMismatch()
        val restored = assertFailsWith<CacheNonceMismatch> {
            restoreRemoteException { throw Throwable(source.message) }
        }
        assertNotSame(source, restored)
        assertNull(restored.cause)
        assertEquals(source.message, restored.message)
        assertEquals(Json.encodeToString<RemoteException>(source), source.message)
        assertEquals(setOf("type"), Json.parseToJsonElement(source.message).jsonObject.keys)
        assertEquals(
            "io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch",
            Json.parseToJsonElement(source.message).jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(3, listOf(
            NoMatchException().message,
            SessionNotActive().message,
            source.message,
        ).toSet().size)
    }

    test("cache nonce restoration does not retry the supplied operation") {
        var attempts = 0
        assertFailsWith<CacheNonceMismatch> {
            restoreRemoteException {
                attempts++
                yield()
                throw Throwable(CacheNonceMismatch().message)
            }
        }
        assertEquals(1, attempts)
    }

    test("the removed generation exception name is not a compatibility alias") {
        val original = Throwable(
            """{"type":"io.github.stream29.kodex.utils.rpcexception.GenerationMismatch"}""",
        )
        assertSame(original, assertFailsWith<Throwable> {
            restoreRemoteException { throw original }
        })
    }

    test("malformed unknown and incomplete JSON preserve the original failure") {
        val valid = NoMatchException().message
        for (message in listOf(
            "{", "{}", """{"type":"unknown.Exception"}""",
            """{"type":null}""", """{"message":"ordinary JSON diagnostic"}""",
            "$valid trailing", valid.dropLast(1) + ""","unexpected":1}""",
        )) {
            val original = Throwable(message)
            assertSame(original, assertFailsWith<Throwable> {
                restoreRemoteException { throw original }
            })
        }
    }

    test("only a leading brace in the top level message enables decoding") {
        val valid = NoMatchException().message
        for (message in listOf(null, "", "ordinary failure", " $valid", "\n$valid", "prefix:$valid")) {
            val original = Throwable(message, Throwable(valid))
            assertSame(original, assertFailsWith<Throwable> {
                restoreRemoteException { throw original }
            })
        }
    }

    test("cancellation with a valid encoded message is never reconstructed") {
        for (failure in listOf(NoMatchException(), SessionNotActive(), CacheNonceMismatch(), SessionNotFound())) {
            val original = CancellationException(failure.message)
            assertSame(original, assertFailsWith<CancellationException> {
                restoreRemoteException { throw original }
            })
        }
    }

    test("inline blocks may suspend before success or remote failure") {
        assertEquals(42, restoreRemoteException {
            yield()
            42
        })
        assertFailsWith<NoMatchException> {
            restoreRemoteException {
                yield()
                throw Throwable(NoMatchException().message)
            }
        }
    }

    test("upstream Flow catch restores failures during collection") {
        val upstream = flow<Int> {
            emit(1)
            yield()
            throw Throwable(NoMatchException().message)
        }.catch { cause ->
            restoreRemoteException { throw cause }
        }
        val values = mutableListOf<Int>()
        assertFailsWith<NoMatchException> { upstream.collect { values += it } }
        assertEquals(listOf(1), values)
    }

    test("upstream adaptation does not intercept downstream exceptions") {
        val downstream = Throwable(NoMatchException().message)
        val upstream = flow { emit(1) }.catch { cause ->
            restoreRemoteException { throw cause }
        }
        assertSame(downstream, assertFailsWith<Throwable> {
            upstream.collect { throw downstream }
        })
    }
}
