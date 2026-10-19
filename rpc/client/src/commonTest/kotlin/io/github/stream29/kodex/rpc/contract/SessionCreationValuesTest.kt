@file:OptIn(kotlinx.rpc.internal.utils.ExperimentalRpcApi::class)

package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxioserialization.PathAsStringSerializer
import kotlinx.io.files.Path
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

val sessionCreationValuesTest by testSuite {
    test("generated GlobalRpc creation descriptor has only a required serialized cwd") {
        val creation = assertNotNull(serviceDescriptorOf<GlobalRpc>().getCallable("createSession"))
        val cwd = creation.parameters.single()
        assertEquals("cwd", cwd.name)
        assertFalse(cwd.isOptional)
        assertEquals(PathAsStringSerializer::class, cwd.type.annotations.filterIsInstance<Serializable>().single().with)
    }

    test("creation path serializer round trips Unicode spaces and quoted path text as a JSON string") {
        for (cwd in listOf(Path("/fixture/项目 with spaces"), Path("relative directory/引号\"目录"))) {
            val encoded = Json.encodeToString(PathAsStringSerializer, cwd)
            assertEquals(JsonPrimitive(cwd.toString()), Json.parseToJsonElement(encoded))
            assertEquals(cwd, Json.decodeFromString(PathAsStringSerializer, encoded))
        }
    }

    test("creation path serializer rejects null and the obsolete complete settings object") {
        for (invalid in listOf("null", """{"model":"fixture-model","cwd":"/fixture"}""")) {
            assertFailsWith<SerializationException> {
                Json.decodeFromString(PathAsStringSerializer, invalid)
            }
        }
    }
}
