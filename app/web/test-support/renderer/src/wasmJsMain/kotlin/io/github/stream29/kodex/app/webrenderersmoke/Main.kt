@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.ui.window.ComposeViewport
import io.github.stream29.kodex.app.ui.UiTheme

public actual fun main(args: Array<String>) {
    val token = takeFixtureToken()
    ComposeViewport {
        io.github.stream29.kodex.app.ui.WithBrowserComponentRenderers {
        UiTheme {
            RendererSmoke(formDialog = { onClose -> HookInputSmoke(onClose) }) { onBack ->
                RpcFixture(token, onBack)
            }
        }
    }
    }
}

@JsFun("""() => {
    const token = decodeURIComponent(location.hash.slice(1));
    history.replaceState(null, "", location.pathname + location.search);
    return token;
}""")
private external fun takeFixtureToken(): String
