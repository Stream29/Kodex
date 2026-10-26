@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.cli.components

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.datetime.TimeZone
import kotlin.test.assertEquals
import kotlin.time.Instant

val browserPopupTimestampTest by testSuite {
    test("system-local formatting uses the browser offset at each instant") {
        for (text in listOf("2026-01-08T01:02:03.456Z", "2026-09-08T01:02:03.456Z")) {
            assertEquals(browserTimestamp(text), formatPopupTimestamp(Instant.parse(text), TimeZone.currentSystemDefault()))
        }
        println("Browser timezone: ${browserTimeZone()}")
    }
}

private fun browserTimestamp(text: String): String = js("""{
    const d = new Date(text), pad = n => String(n).padStart(2, '0');
    const offset = -d.getTimezoneOffset(), minutes = Math.abs(offset);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' +
        pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()) + ' UTC' +
        (offset < 0 ? '-' : '+') + pad(Math.floor(minutes / 60)) + ':' + pad(minutes % 60);
}""")

private fun browserTimeZone(): String = js("Intl.DateTimeFormat().resolvedOptions().timeZone")
