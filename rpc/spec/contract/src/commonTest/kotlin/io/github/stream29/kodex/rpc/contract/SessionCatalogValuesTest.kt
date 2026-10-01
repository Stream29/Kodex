package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

val sessionCatalogValuesTest by testSuite {
    val json = Json { encodeDefaults = true }
    val serializer = SessionCatalogEntry.serializer()

    test("catalog entry preserves metadata and sampled running and active flags") {
        val entry = SessionCatalogEntry(
            sessionIndex = 42,
            threadName = "Test session",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-02T03:04:05Z"),
            archived = true,
            running = true,
            isActive = true,
        )
        val encoded = json.encodeToJsonElement(serializer, entry)
        assertEquals(
            setOf("sessionIndex", "threadName", "createdAt", "updatedAt", "archived", "running", "isActive"),
            encoded.jsonObject.keys,
        )
        assertEquals(entry, json.decodeFromJsonElement(serializer, encoded))
    }

    test("active idle and running snapshots are independent of archived and default to false") {
        assertEquals(false, SessionCatalogEntry(0).running)
        assertEquals(false, SessionCatalogEntry(0).isActive)
        assertEquals(false, json.decodeFromString(serializer, """{"sessionIndex":0}""").running)
        assertEquals(false, json.decodeFromString(serializer, """{"sessionIndex":0}""").isActive)
        for (archived in listOf(false, true)) {
            for ((active, running) in listOf(false to false, true to false, true to true)) {
                val entry = SessionCatalogEntry(1, archived = archived, running = running, isActive = active)
                assertEquals(
                    entry,
                    json.decodeFromString(serializer, json.encodeToString(serializer, entry)),
                )
            }
        }
    }

    test("uninitialized metadata list order and empty catalogs round trip") {
        val uninitialized = SessionCatalogEntry(0)
        assertEquals(
            uninitialized,
            json.decodeFromString(serializer, """{"sessionIndex":0}"""),
        )
        val entries = listOf(SessionCatalogEntry(8, "Latest"), uninitialized)
        val listSerializer = ListSerializer(serializer)
        assertEquals(
            entries,
            json.decodeFromString(listSerializer, json.encodeToString(listSerializer, entries)),
        )
        assertEquals(emptyList(), json.decodeFromString(listSerializer, "[]"))
    }

    test("decoding preserves existing index and title validation") {
        for (encoded in listOf(
            """{"sessionIndex":-1}""",
            """{"sessionIndex":1,"threadName":" "}""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                json.decodeFromString(serializer, encoded)
            }
        }
    }
}
