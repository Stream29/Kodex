package io.github.stream29.kodex.cli.settings

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
val kodexAuthSourceTest by testSuite {
    test("original auth descriptor and both wire names are preserved") {
        val serializer = KodexAuthSource.serializer()
        assertEquals("io.github.stream29.kodex.cli.settings.KodexAuthSource", serializer.descriptor.serialName)
        assertEquals(listOf(KodexAuthSource.Codex, KodexAuthSource.Kodex), KodexAuthSource.entries)
        for ((value, wire) in listOf(KodexAuthSource.Codex to "codex", KodexAuthSource.Kodex to "kodex")) {
            assertEquals("\"$wire\"", Json.encodeToString(serializer, value))
            assertEquals(value, Json.decodeFromString(serializer, "\"$wire\""))
        }
    }
}
