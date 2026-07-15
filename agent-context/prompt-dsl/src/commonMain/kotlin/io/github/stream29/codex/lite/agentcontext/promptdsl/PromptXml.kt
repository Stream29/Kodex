package io.github.stream29.codex.lite.agentcontext.promptdsl

/** Marks the receiver scope used to construct XML-shaped prompt content. */
@DslMarker
public annotation class PromptXmlDsl

/**
 * Builds XML-shaped prompt content with escaped text and attributes.
 *
 * The builder is deliberately text-oriented: it does not parse or validate
 * XML, because prompt fragments may contain trusted non-XML content through
 * [rawText].
 */
@PromptXmlDsl
public class PromptXmlBuilder internal constructor(
    private val indented: Boolean,
) {
    private val content: StringBuilder = StringBuilder()
    private var containsTag: Boolean = false

    /** Appends XML-escaped text content. */
    public fun text(value: String): Unit {
        content.append(value.escapeXmlText())
    }

    /** Appends XML-escaped text content. */
    public operator fun String.unaryPlus(): Unit = text(this)

    /**
     * Appends trusted prompt content without XML escaping.
     *
     * Use this only where the surrounding prompt format intentionally accepts
     * raw text, such as AGENTS.md instruction bodies.
     */
    public fun rawText(value: String): Unit {
        content.append(value)
    }

    /** Adds one XML tag and its nested prompt content. */
    public fun tag(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        block: PromptXmlBuilder.() -> Unit = {},
    ): Unit {
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

    internal fun build(): String = content.toString()

    private fun appendTag(value: String): Unit {
        if (indented && content.isNotEmpty() && content.last() != '\n') {
            content.append('\n')
        }
        content.append(value)
        containsTag = true
    }
}

/** Builds XML-shaped prompt content. */
public fun promptXml(
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
