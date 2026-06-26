package io.github.stream29.codex.lite.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAiSubscriptionAuthStateTest {
    @Test
    fun planParsesKnownRawValuesAndAliases() {
        assertEquals(OpenAiSubscriptionPlan.Pro, OpenAiSubscriptionPlan.fromRawValue("pro"))
        assertEquals(OpenAiSubscriptionPlan.ProLite, OpenAiSubscriptionPlan.fromRawValue("prolite"))
        assertEquals(
            OpenAiSubscriptionPlan.SelfServeBusinessUsageBased,
            OpenAiSubscriptionPlan.fromRawValue("self_serve_business_usage_based"),
        )
        assertEquals(OpenAiSubscriptionPlan.Enterprise, OpenAiSubscriptionPlan.fromRawValue("hc"))
        assertEquals(OpenAiSubscriptionPlan.Edu, OpenAiSubscriptionPlan.fromRawValue("education"))
    }

    @Test
    fun planRejectsUnknownRawValues() {
        assertNull(OpenAiSubscriptionPlan.fromRawValue("future-plan"))
    }
}
