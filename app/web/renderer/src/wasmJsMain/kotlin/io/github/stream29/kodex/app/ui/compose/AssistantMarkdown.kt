package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.rememberStreamingMarkdownState

internal val LocalAssistantMarkdownRaw = staticCompositionLocalOf { false }
internal val LocalAssistantStreamEligibility = staticCompositionLocalOf<(Boolean?) -> Unit> { {} }

internal fun isSafeAssistantMarkdownUrl(value: String): Boolean {
    val prefix = when {
        value.startsWith("https://", ignoreCase = true) -> 8
        value.startsWith("http://", ignoreCase = true) -> 7
        else -> return false
    }
    if (value.length == prefix || value[prefix] in "/?#@" ||
        value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }
    ) return false
    return true
}

internal class AssistantMarkdownUriHandler(private val delegate: UriHandler) : UriHandler {
    override fun openUri(uri: String) {
        if (!isSafeAssistantMarkdownUrl(uri)) return
        try {
            delegate.openUri(uri)
        } catch (_: Throwable) {
            // This synchronous click has no owned wait; prevent upstream from logging the URL.
        }
    }
}

@Composable
internal fun AssistantMarkdownText(value: String) {
    var revision by remember { mutableIntStateOf(0) }
    val markdown = key(revision) { rememberStreamingMarkdownState() }
    LaunchedEffect(value, markdown) {
        val current = markdown.content.toString()
        if (!value.startsWith(current)) {
            revision++
        } else if (value.length > current.length) {
            markdown.append(value.substring(current.length))
        }
    }
    val delegate = LocalUriHandler.current
    val guarded = remember(delegate) { AssistantMarkdownUriHandler(delegate) }
    CompositionLocalProvider(LocalUriHandler provides guarded) {
        SelectionContainer {
            if (LocalAssistantMarkdownRaw.current || !value.startsWith(markdown.content.toString())) {
                Text(value, Modifier.fillMaxWidth())
            } else {
                Markdown(
                    streamingMarkdownState = markdown,
                    typography = assistantMarkdownTypography(),
                    modifier = Modifier.fillMaxWidth(),
                    imageTransformer = NoOpImageTransformerImpl(),
                )
            }
        }
    }
}

@Composable
internal fun assistantMarkdownTypography(): MarkdownTypography {
    val body = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
    val h1 = body.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val h2 = body.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val subheading = body.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    return markdownTypography(
        h1 = h1,
        h2 = h2,
        h3 = subheading,
        h4 = subheading,
        h5 = subheading,
        h6 = subheading,
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        table = body,
    )
}
