package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

val subagentCreationModeTest by testSuite {
    test("old settings default to auto approve while both explicit wire values decode") {
        val serializer = KodexAgentSettings.serializer()
        fun read(extra: String) = OpenAiJsonCodec.decodeFromString(serializer, """{"model":"fixture"$extra}""")
        assertEquals(SubagentCreationMode.AutoApprove, read("").subagentCreationMode)
        assertEquals(SubagentCreationMode.AutoApprove, read(""","subagentCreationMode":"auto_approve"""").subagentCreationMode)
        assertEquals(SubagentCreationMode.Reject, read(""","subagentCreationMode":"reject"""").subagentCreationMode)
        val value = KodexAgentSettings(OpenAiModelId("fixture"), subagentCreationMode = SubagentCreationMode.Reject)
        val payload = OpenAiJsonCodec.encodeToJsonElement(serializer, value).jsonObject
        assertEquals(JsonPrimitive("reject"), payload.getValue("subagentCreationMode"))
        assertEquals(value, OpenAiJsonCodec.decodeFromString(serializer, payload.toString()))
    }
}
