package io.github.stream29.kodex.app.sessioncatalog.contract

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

val sessionCatalogContractTest by testSuite {
    val json = Json { encodeDefaults = true }
    val serializer = SessionCatalogEntry.serializer()

    test("entryKeepsPersistedIdentityAndValidatesDisplayName") {
        val entry = SessionCatalogEntry(
            sessionIndex = 3,
            threadName = "Thread",
        )

        assertEquals(3, entry.sessionIndex)
        assertEquals("Thread", entry.threadName)
        assertFailsWith<IllegalArgumentException> {
            entry.copy(sessionIndex = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            entry.copy(threadName = " ")
        }
    }

    test("catalogDatesRoundTripWithoutADuplicateActivityField") {
        val entry = SessionCatalogEntry(
            sessionIndex = 3,
            threadName = "Thread",
            createdAt = Instant.parse("2026-01-01T00:00:00.123456789Z"),
            updatedAt = Instant.parse("2026-01-02T03:04:05.987654321Z"),
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

    test("catalogDatesAreIndependentlyNullableAndDefaultToAbsent") {
        val empty = SessionCatalogEntry(0)
        assertNull(empty.createdAt)
        assertNull(empty.updatedAt)
        assertEquals(empty, json.decodeFromString(serializer, """{"sessionIndex":0}"""))
        val timestamp = Instant.parse("2026-01-02T03:04:05Z")
        for (created in listOf(null, timestamp)) {
            for (updated in listOf(null, timestamp)) {
                val entry = SessionCatalogEntry(1, createdAt = created, updatedAt = updated)
                assertEquals(
                    entry,
                    json.decodeFromString(serializer, json.encodeToString(serializer, entry)),
                )
            }
        }
    }

    test("catalogActivityDefaultsToFalseWithoutInferringItFromMetadata") {
        val entry = SessionCatalogEntry(
            sessionIndex = 3,
            threadName = "Persisted",
            updatedAt = Instant.parse("2026-01-02T03:04:05Z"),
        )
        assertEquals(false, entry.isActive)
        assertEquals(false, entry.running)
        val decoded = json.decodeFromString(serializer, """{"sessionIndex":3}""")
        assertEquals(false, decoded.isActive)
        assertEquals(false, decoded.running)
    }

    test("catalogPreservesUnloadedIdleAndRunningSnapshotsRegardlessOfArchive") {
        for (archived in listOf(false, true)) {
            for ((active, running) in listOf(false to false, true to false, true to true)) {
                val entry = SessionCatalogEntry(3, archived = archived, running = running, isActive = active)
                assertEquals(
                    entry,
                    json.decodeFromString(serializer, json.encodeToString(serializer, entry)),
                )
            }
        }
    }

    test("catalogDateSnapshotsPreserveListOrderAndValues") {
        val entries = listOf(
            SessionCatalogEntry(
                sessionIndex = 8,
                createdAt = Instant.parse("2026-01-02T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
            SessionCatalogEntry(0),
        )
        val listSerializer = ListSerializer(serializer)
        assertEquals(
            entries,
            json.decodeFromString(listSerializer, json.encodeToString(listSerializer, entries)),
        )
        assertEquals(emptyList(), json.decodeFromString(listSerializer, "[]"))
    }
}
