package io.github.stream29.kodex.openai

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.json.Json

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

    test("ProMax recognizes raw claims case insensitively without reinterpreting product names") {
        for (raw in listOf("promax", "ProMax", "PROMAX")) {
            assertEquals(OpenAiSubscriptionPlan.ProMax, OpenAiSubscriptionPlan.fromRawValue(raw))
        }
        assertEquals("promax", OpenAiSubscriptionPlan.ProMax.rawValue)
        for (unknown in listOf("pro500", "pro_max", "future-plan")) {
            assertNull(OpenAiSubscriptionPlan.fromRawValue(unknown))
        }
    }

    test("ProMax appends to existing plans and retains authentication summary wire names") {
        val legacy = listOf(
            OpenAiSubscriptionPlan.Free, OpenAiSubscriptionPlan.Go, OpenAiSubscriptionPlan.Plus,
            OpenAiSubscriptionPlan.Pro, OpenAiSubscriptionPlan.ProLite, OpenAiSubscriptionPlan.Team,
            OpenAiSubscriptionPlan.SelfServeBusinessUsageBased, OpenAiSubscriptionPlan.Business,
            OpenAiSubscriptionPlan.EnterpriseCbpUsageBased, OpenAiSubscriptionPlan.Enterprise,
            OpenAiSubscriptionPlan.Edu,
        )
        assertEquals(legacy + OpenAiSubscriptionPlan.ProMax, OpenAiSubscriptionPlan.entries.toList())
        assertEquals(
            listOf("free", "go", "plus", "pro", "prolite", "team", "self_serve_business_usage_based",
                "business", "enterprise_cbp_usage_based", "enterprise", "edu"),
            legacy.map { it.rawValue },
        )
        for (plan in OpenAiSubscriptionPlan.entries) {
            val encoded = Json.encodeToString(OpenAiSubscriptionPlan.serializer(), plan)
            assertEquals("\"${plan.name}\"", encoded)
            assertEquals(plan, Json.decodeFromString(OpenAiSubscriptionPlan.serializer(), encoded))
        }
    }
}
