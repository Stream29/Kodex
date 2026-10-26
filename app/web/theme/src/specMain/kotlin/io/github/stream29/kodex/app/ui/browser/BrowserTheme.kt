package io.github.stream29.kodex.app.ui.browser

import androidx.compose.runtime.Composable

/** Browser theme with packaged Chinese glyph fallback; mounts content only after fonts are ready. */
@Composable
public expect fun BrowserTheme(content: @Composable () -> Unit): Unit

/** Packaged typefaces registered for browser-native input elements before themed content mounts. */
public const val BrowserInputFontFamily: String = "'Kodex Browser SC', 'Kodex Browser Math', sans-serif"
