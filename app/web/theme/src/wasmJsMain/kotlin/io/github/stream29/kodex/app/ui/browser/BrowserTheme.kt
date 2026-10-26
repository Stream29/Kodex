@file:OptIn(
    org.jetbrains.compose.resources.ExperimentalResourceApi::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
)

package io.github.stream29.kodex.app.ui.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.platform.Font
import io.github.stream29.kodex.app.ui.browser.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.js

@Composable
public actual fun BrowserTheme(content: @Composable () -> Unit) {
    val resolver = LocalFontFamilyResolver.current
    val status by produceState<FontStatus>(FontStatus.Loading, resolver) {
        try {
            val bytes = Res.readBytes("font/noto_sans_sc.ttf")
            val family = FontFamily(Font(
                "NotoSansSC", bytes,
                variationSettings = FontVariation.Settings(FontVariation.weight(400)),
            ))
            resolver.preload(family)
            resolver.preload(FontFamily(Font("NotoSansMath", Res.readBytes("font/noto_sans_math.ttf"))))
            domFonts.await()
            value = FontStatus.Ready(family)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = FontStatus.Failed
        }
    }
    val ready = status as? FontStatus.Ready
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) BrowserDarkColors else BrowserLightColors,
        typography = browserTypography(ready?.family ?: FontFamily.SansSerif),
    ) {
        Surface(Modifier.fillMaxSize()) {
            when (status) {
                is FontStatus.Ready -> content()
                FontStatus.Loading -> Text("Loading packaged fonts…")
                FontStatus.Failed -> Text("Unable to load packaged fonts. Reload the page to retry.")
            }
        }
    }
}

private sealed interface FontStatus {
    data object Loading : FontStatus
    data class Ready(val family: FontFamily) : FontStatus
    data object Failed : FontStatus
}

private val BrowserLightColors = lightColorScheme(
    primary = Color(0xFF315DA8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF), onPrimaryContainer = Color(0xFF001A42),
    secondary = Color(0xFF565F71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9), onSecondaryContainer = Color(0xFF131C2C),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF191C23),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEDF4), surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E9),
    onSurfaceVariant = Color(0xFF44474F), outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
    background = Color(0xFFF9F9FF), onBackground = Color(0xFF191C23),
)

private val BrowserDarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF), onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF124584), onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBEC6DC), onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFDAE2F9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE1E2E9),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C23),
    surfaceContainer = Color(0xFF1D2027), surfaceContainerHigh = Color(0xFF282A31),
    surfaceContainerHighest = Color(0xFF33353C),
    onSurfaceVariant = Color(0xFFC4C6D0), outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474F),
    background = Color(0xFF111318), onBackground = Color(0xFFE1E2E9),
)

private fun browserTypography(family: FontFamily): Typography {
    val baseline = Typography()
    return baseline.copy(
        displayLarge = baseline.displayLarge.copy(fontFamily = family),
        displayMedium = baseline.displayMedium.copy(fontFamily = family),
        displaySmall = baseline.displaySmall.copy(fontFamily = family),
        headlineLarge = baseline.headlineLarge.copy(fontFamily = family),
        headlineMedium = baseline.headlineMedium.copy(fontFamily = family),
        headlineSmall = baseline.headlineSmall.copy(fontFamily = family),
        titleLarge = baseline.titleLarge.copy(fontFamily = family),
        titleMedium = baseline.titleMedium.copy(fontFamily = family),
        titleSmall = baseline.titleSmall.copy(fontFamily = family),
        bodyLarge = baseline.bodyLarge.copy(fontFamily = family),
        bodyMedium = baseline.bodyMedium.copy(fontFamily = family),
        bodySmall = baseline.bodySmall.copy(fontFamily = family),
        labelLarge = baseline.labelLarge.copy(fontFamily = family),
        labelMedium = baseline.labelMedium.copy(fontFamily = family),
        labelSmall = baseline.labelSmall.copy(fontFamily = family),
    )
}

private val domFonts by lazy {
    loadDomFonts(
        resolveDomResourceUri(Res.getUri("font/noto_sans_sc.ttf")),
        resolveDomResourceUri(Res.getUri("font/noto_sans_math.ttf")),
    )
}

// The pinned resource helper appends to location.href, including an explicit index.html.
private fun resolveDomResourceUri(uri: String): String = js("""
    new URL(uri.startsWith(location.href) ? uri.slice(location.href.length) : uri, document.baseURI).href
""")

private fun loadDomFonts(sc: String, math: String): Promise<JsAny?> = js("""
    Promise.all([
        new FontFace("Kodex Browser SC", "url('" + sc + "')", {weight:"100 900"}).load(),
        new FontFace("Kodex Browser Math", "url('" + math + "')").load()
    ]).then(fonts => { fonts.forEach(font => document.fonts.add(font)); return null; })
""")
