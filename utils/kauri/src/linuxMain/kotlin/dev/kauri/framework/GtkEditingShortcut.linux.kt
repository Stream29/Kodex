package dev.kauri.framework

internal enum class GtkEditingCommand { Undo, Redo }

internal fun gtkEditingShortcut(key: UInt, modifiers: UInt): GtkEditingCommand? {
    val control = 1u shl 2
    val shift = 1u
    val locks = (1u shl 1) or (1u shl 4)
    if (modifiers and control == 0u || modifiers and (control or shift or locks).inv() != 0u) return null
    return when (key) {
        'z'.code.toUInt(), 'Z'.code.toUInt() ->
            if (modifiers and shift != 0u) GtkEditingCommand.Redo else GtkEditingCommand.Undo
        'y'.code.toUInt(), 'Y'.code.toUInt() ->
            if (modifiers and shift == 0u) GtkEditingCommand.Redo else null
        else -> null
    }
}
