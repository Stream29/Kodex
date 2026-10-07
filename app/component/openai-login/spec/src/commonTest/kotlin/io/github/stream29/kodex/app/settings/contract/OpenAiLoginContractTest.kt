package io.github.stream29.kodex.app.settings.contract

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertFailsWith

val openAiLoginContractTest by testSuite {
    test("attempt state and effects require positive identities") {
        for (id in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> { OpenAiLoginState.WaitingForAuthorization(id) }
            assertFailsWith<IllegalArgumentException> { OpenAiLoginState.BrowserOpenFailed(id) }
            assertFailsWith<IllegalArgumentException> {
                OpenAiLoginEffect.OpenExternalUrl(id, "https://example.invalid")
            }
        }
    }

    test("failure messages and authorization URLs must not be blank") {
        for (blank in listOf("", " ", "\n")) {
            assertFailsWith<IllegalArgumentException> { OpenAiLoginState.Failed(blank) }
            assertFailsWith<IllegalArgumentException> { OpenAiLoginEffect.OpenExternalUrl(1L, blank) }
        }
    }
}
