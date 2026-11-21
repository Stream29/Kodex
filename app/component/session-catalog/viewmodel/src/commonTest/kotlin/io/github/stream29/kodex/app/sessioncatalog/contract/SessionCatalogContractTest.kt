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
            name = "Thread",
            rootAgentId = 103,
        )

        assertEquals(3, entry.sessionIndex)
        assertEquals(103, entry.rootAgentId)
        assertEquals("Thread", entry.name)
        assertFailsWith<IllegalArgumentException> {
            entry.copy(sessionIndex = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            entry.copy(rootAgentId = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            entry.copy(name = " ")
        }
    }

    test("catalogDatesRoundTripWithoutADuplicateActivityField") {
        val entry = SessionCatalogEntry(
            sessionIndex = 3,
            name = "Thread",
            createdAt = Instant.parse("2026-01-01T00:00:00.123456789Z"),
            updatedAt = Instant.parse("2026-01-02T03:04:05.987654321Z"),
            archived = true,
            running = true,
            isActive = true,
            rootAgentId = 103,
        )
        val encoded = json.encodeToJsonElement(serializer, entry)

        assertEquals(
            setOf("sessionIndex", "name", "createdAt", "updatedAt", "archived", "running", "isActive", "rootAgentId"),
            encoded.jsonObject.keys,
        )
        assertEquals(entry, json.decodeFromJsonElement(serializer, encoded))
    }

    test("catalogDatesAreIndependentlyNullableAndDefaultToAbsent") {
        val empty = SessionCatalogEntry(sessionIndex = 0, rootAgentId = 100)
        assertNull(empty.createdAt)
        assertNull(empty.updatedAt)
        assertEquals(empty, json.decodeFromString(serializer, """{"sessionIndex":0,"rootAgentId":100}"""))
        val timestamp = Instant.parse("2026-01-02T03:04:05Z")
        for (created in listOf(null, timestamp)) {
            for (updated in listOf(null, timestamp)) {
                val entry = SessionCatalogEntry(1, createdAt = created, updatedAt = updated, rootAgentId = 101)
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
            name = "Persisted",
            updatedAt = Instant.parse("2026-01-02T03:04:05Z"),
            rootAgentId = 103,
        )
        assertEquals(false, entry.isActive)
        assertEquals(false, entry.running)
        val decoded = json.decodeFromString(serializer, """{"sessionIndex":3,"rootAgentId":103}""")
        assertEquals(103, decoded.rootAgentId)
        assertEquals(false, decoded.isActive)
        assertEquals(false, decoded.running)
    }

    test("catalogPreservesUnloadedIdleAndRunningSnapshotsRegardlessOfArchive") {
        for (archived in listOf(false, true)) {
            for ((active, running) in listOf(false to false, true to false, true to true)) {
                val entry = SessionCatalogEntry(
                    sessionIndex = 3,
                    archived = archived,
                    running = running,
                    isActive = active,
                    rootAgentId = 103,
                )
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
                rootAgentId = 108,
            ),
            SessionCatalogEntry(sessionIndex = 0, rootAgentId = 100),
        )
        val listSerializer = ListSerializer(serializer)
        assertEquals(
            entries,
            json.decodeFromString(listSerializer, json.encodeToString(listSerializer, entries)),
        )
        assertEquals(emptyList(), json.decodeFromString(listSerializer, "[]"))
    }
}
