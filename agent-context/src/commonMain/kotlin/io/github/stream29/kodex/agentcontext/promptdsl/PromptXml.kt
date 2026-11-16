package io.github.stream29.kodex.agentcontext.promptdsl

@DslMarker
internal annotation class PromptXmlDsl

@PromptXmlDsl
internal class PromptXmlBuilder(
    private val indented: Boolean,
) {
    private val content: StringBuilder = StringBuilder()
    private var containsTag: Boolean = false

    fun text(value: String) {
        content.append(value.escapeXmlText())
    }

    operator fun String.unaryPlus(): Unit = text(this)

    fun rawText(value: String) {
        content.append(value)
    }

    fun tag(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        block: PromptXmlBuilder.() -> Unit = {},
    ) {
        val child = PromptXmlBuilder(indented).apply(block)
        val childContent = child.build()
        val openingTag = name.openingTag(attributes)
        val rendered = when {
            childContent.isEmpty() -> "$openingTag/>"
            !indented || (!child.containsTag && '\n' !in childContent) ->
                "$openingTag>$childContent</$name>"

            else -> buildString {
                append(openingTag)
                append('>')
                append('\n')
                append(childContent.indent())
                append('\n')
                append("</")
                append(name)
                append('>')
            }
        }
        appendTag(rendered)
    }

    fun build(): String = content.toString()

    private fun appendTag(value: String) {
        if (indented && content.isNotEmpty() && content.last() != '\n') {
            content.append('\n')
        }
        content.append(value)
        containsTag = true
    }
}

internal fun promptXml(
    indented: Boolean = true,
    block: PromptXmlBuilder.() -> Unit,
): String = PromptXmlBuilder(indented).apply(block).build()

private fun String.openingTag(attributes: Map<String, String>): String = buildString {
    append('<')
    append(this@openingTag)
    attributes.forEach { (name, value) ->
        append(' ')
        append(name)
        append("=\"")
        append(value.escapeXmlAttribute())
        append('\"')
    }
}

private fun String.indent(): String = lineSequence().joinToString("\n") { line -> "  $line" }

private fun String.escapeXmlText(): String = buildString {
    this@escapeXmlText.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                else -> character
            },
        )
    }
}

private fun String.escapeXmlAttribute(): String = buildString {
    this@escapeXmlAttribute.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '\"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character
            },
        )
    }
}
