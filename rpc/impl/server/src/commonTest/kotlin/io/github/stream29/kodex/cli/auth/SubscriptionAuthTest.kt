package io.github.stream29.kodex.cli.auth

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthJson
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthMode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Valid format, JWT and scheduling assertions retained from the retired local-loader suite. */
val subscriptionAuthTest by testSuite {
    test("PKCE S256 code challenge follows RFC 7636") {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            pkceCodeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }
    test("access token expiry schedules refresh five minutes early") {
        val expiresAt = Instant.parse("2030-01-01T03:00:00Z")
        val tokens = authHelperTokens().copy(accessToken = authHelperJwt(expiresAt))
        assertEquals(expiresAt - 5.minutes, subscriptionRefreshAt(tokens, expiresAt - 4.days))
    }
    test("opaque access token falls back to eight days from the last refresh") {
        val lastRefresh = Instant.parse("2030-01-01T00:00:00Z")
        assertEquals(lastRefresh + 8.days, subscriptionRefreshAt(authHelperTokens(), lastRefresh))
    }
    test("ID token account plan and email survive both format conversions") {
        val refreshedAt = Instant.parse("2030-01-01T00:00:00Z")
        val tokens = authHelperTokens().copy(idToken = authHelperJwt(refreshedAt + 1.days), accountId = null)
        val codex = assertIs<AuthLoadResult.Loaded>(
            CodexAuthJson(authMode = CodexAuthMode.Chatgpt, tokens = tokens, lastRefresh = refreshedAt).toAuthLoadResult(),
        )
        val kodex = assertIs<AuthLoadResult.Loaded>(
            KodexAuthFile(CodexAuthMode.Chatgpt, tokens, refreshedAt).toAuthLoadResult(),
        )
        assertEquals(codex.auth, kodex.auth)
        assertEquals("jwt-account", codex.auth.publicState.accountId)
        assertEquals(OpenAiSubscriptionPlan.Plus, codex.auth.publicState.planType)
        assertEquals("jwt@example.com", codex.auth.publicState.email)
        assertEquals("opaque-access", codex.auth.publicState.accessToken)
        assertEquals(
            "explicit-account",
            codex.auth.copy(tokens = tokens.copy(accountId = "explicit-account")).publicState.accountId,
        )
        assertEquals(
            "jwt-account",
            codex.auth.copy(tokens = tokens.copy(accountId = " ")).publicState.accountId,
        )
    }
    test("Codex subscription modes are accepted while unsupported or missing credentials stay unavailable") {
        val refreshedAt = Instant.parse("2030-01-01T00:00:00Z")
        for (mode in listOf(null, CodexAuthMode.Chatgpt, CodexAuthMode.ChatgptAuthTokens)) {
            assertIs<AuthLoadResult.Loaded>(
                CodexAuthJson(authMode = mode, tokens = authHelperTokens(), lastRefresh = refreshedAt).toAuthLoadResult(),
            )
        }
        assertEquals(OpenAiAuthState.Unavailable.UnsupportedAuthMode, assertIs<AuthLoadResult.Unavailable>(
            CodexAuthJson(authMode = CodexAuthMode.ApiKey, openAiApiKey = "test-key").toAuthLoadResult(),
        ).reason)
        assertEquals(OpenAiAuthState.Unavailable.InvalidCredentials, assertIs<AuthLoadResult.Unavailable>(
            CodexAuthJson(authMode = CodexAuthMode.Chatgpt).toAuthLoadResult(),
        ).reason)
        assertEquals(OpenAiAuthState.Unavailable.UnsupportedAuthMode, assertIs<AuthLoadResult.Unavailable>(
            KodexAuthFile(CodexAuthMode.ApiKey, authHelperTokens(), refreshedAt).toAuthLoadResult(),
        ).reason)
    }
    test("partial refresh preserves omitted ID and refresh tokens and changes only refresh timestamp") {
        val refreshedAt = Instant.parse("2030-01-01T00:00:00Z")
        val original = ActiveSubscriptionAuth(authHelperTokens(), refreshedAt)
        val refreshed = original.refreshed(
            OpenAiSubscriptionTokenRefresh(accessToken = "rotated-access"),
            refreshedAt + 1.days,
        )
        assertEquals(original.tokens.copy(accessToken = "rotated-access"), refreshed.tokens)
        assertEquals(refreshedAt + 1.days, refreshed.lastRefresh)
        assertEquals(original.tokens, original.refreshed(OpenAiSubscriptionTokenRefresh(), refreshedAt).tokens)
    }
}

private fun authHelperTokens() = OpenAiSubscriptionTokens("opaque-id", "opaque-access", "refresh", "account")

private fun authHelperJwt(expiresAt: Instant): String {
    val payload = buildJsonObject {
        put("exp", expiresAt.epochSeconds)
        put("email", "jwt@example.com")
        put("https://api.openai.com/auth", buildJsonObject {
            put("chatgpt_account_id", "jwt-account")
            put("chatgpt_plan_type", "plus")
        })
    }
    return "e30." + Base64.UrlSafe.encode(payload.toString().encodeToByteArray()).trimEnd('=') + "."
}
