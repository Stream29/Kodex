package io.github.stream29.codex.lite.openai

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertEquals
import kotlin.test.assertNull



val openAiSubscriptionAuthStateTest by testSuite {
    test("plan parses known raw values and aliases") {
        assertEquals(OpenAiSubscriptionPlan.Pro, OpenAiSubscriptionPlan.fromRawValue("pro"))
        assertEquals(OpenAiSubscriptionPlan.ProLite, OpenAiSubscriptionPlan.fromRawValue("prolite"))
        assertEquals(
            OpenAiSubscriptionPlan.SelfServeBusinessUsageBased,
            OpenAiSubscriptionPlan.fromRawValue("self_serve_business_usage_based"),
        )
        assertEquals(OpenAiSubscriptionPlan.Enterprise, OpenAiSubscriptionPlan.fromRawValue("hc"))
        assertEquals(OpenAiSubscriptionPlan.Edu, OpenAiSubscriptionPlan.fromRawValue("education"))
    }

    test("plan rejects unknown raw values") {
        assertNull(OpenAiSubscriptionPlan.fromRawValue("future-plan"))
    }
}
