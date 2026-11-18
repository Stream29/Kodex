package io.github.stream29.kodex.app.application.contract

/** Incremental UTF-8 decoding followed by a streaming terminal-control filter (not a terminal emulator). */
internal class TerminalOutputDecoder(private val append: (Int) -> Unit) {
    private enum class Control { Text, Escape, EscapeIntermediate, Csi, String, Osc, StringEscape, OscEscape }

    private var needed = 0
    private var codePoint = 0
    private var lower = 0x80
    private var upper = 0xbf
    private var afterCr = false
    private var control = Control.Text

    fun feed(bytes: ByteArray) {
        for (byte in bytes) decode(byte.toInt() and 0xff)
    }

    private fun decode(byte: Int) {
        if (needed != 0) {
            if (byte in lower..upper) {
                lower = 0x80
                upper = 0xbf
                codePoint = (codePoint shl 6) or (byte and 0x3f)
                if (--needed == 0) accept(codePoint)
                return
            }
            needed = 0
            lower = 0x80
            upper = 0xbf
            accept(0xfffd)
        }
        when (byte) {
            in 0..0x7f -> accept(byte)
            in 0xc2..0xdf -> { needed = 1; codePoint = byte and 0x1f }
            in 0xe0..0xef -> {
                needed = 2
                codePoint = byte and 0x0f
                if (byte == 0xe0) lower = 0xa0
                if (byte == 0xed) upper = 0x9f
            }
            in 0xf0..0xf4 -> {
                needed = 3
                codePoint = byte and 0x07
                if (byte == 0xf0) lower = 0x90
                if (byte == 0xf4) upper = 0x8f
            }
            else -> accept(0xfffd)
        }
    }

    /** Ends one contiguous source range; incomplete controls cannot cross an omission or EOF. */
    fun finish() {
        if (needed != 0) accept(0xfffd)
        needed = 0
        lower = 0x80
        upper = 0xbf
        afterCr = false
        control = Control.Text
    }

    private fun accept(cp: Int) {
        if (afterCr) {
            afterCr = false
            if (cp == 0x0a) return
        }
        val inString = control == Control.String || control == Control.Osc ||
            control == Control.StringEscape || control == Control.OscEscape
        if (cp == 0x18 || cp == 0x1a || cp == 0x9c) {
            control = Control.Text
            return
        }
        if (inString) {
            val osc = control == Control.Osc || control == Control.OscEscape
            control = when {
                cp == 0x07 && osc -> Control.Text
                cp == 0x5c && (control == Control.StringEscape || control == Control.OscEscape) -> Control.Text
                cp == 0x1b -> if (osc) Control.OscEscape else Control.StringEscape
                else -> if (osc) Control.Osc else Control.String
            }
            return
        }
        when (cp) {
            0x1b -> { control = Control.Escape; return }
            0x9b -> { control = Control.Csi; return }
            0x9d -> { control = Control.Osc; return }
            0x90, 0x98, 0x9e, 0x9f -> { control = Control.String; return }
        }
        when (control) {
            Control.Escape -> {
                control = when (cp) {
                    0x5b -> Control.Csi
                    0x5d -> Control.Osc
                    0x50, 0x58, 0x5e, 0x5f -> Control.String
                    in 0x20..0x2f -> Control.EscapeIntermediate
                    else -> Control.Text
                }
                return
            }
            Control.EscapeIntermediate -> {
                if (cp in 0x30..0x7e) control = Control.Text
                return
            }
            Control.Csi -> {
                if (cp in 0x40..0x7e) control = Control.Text
                return
            }
            else -> Unit
        }
        when (cp) {
            0x0a -> append(cp)
            0x0d -> { append(0x0a); afterCr = true }
            // Fixed spaces avoid delegating tab/column interpretation to a terminal renderer.
            0x09 -> repeat(4) { append(0x20) }
            in 0..0x1f, in 0x7f..0x9f -> Unit
            else -> append(cp)
        }
    }
}
