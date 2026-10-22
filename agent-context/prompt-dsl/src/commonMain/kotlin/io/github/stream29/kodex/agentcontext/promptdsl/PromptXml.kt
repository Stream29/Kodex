@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentcontext.promptdsl

/**
 * Builds XML-shaped prompt content with escaped text and attributes.
 *
 * The builder is deliberately text-oriented: it does not parse or validate
 * XML, because prompt fragments may contain trusted non-XML content through
 * [rawText].
 */
@PromptXmlDsl
public actual class PromptXmlBuilder internal constructor(
    private val indented: Boolean,
) {
    private val content: StringBuilder = StringBuilder()
    private var containsTag: Boolean = false

    /** Appends XML-escaped text content. */
    public actual fun text(value: String): Unit {
        content.append(value.escapeXmlText())
    }

    /** Appends XML-escaped text content. */
    public actual operator fun String.unaryPlus(): Unit = text(this)

    /**
     * Appends trusted prompt content without XML escaping.
     *
     * Use this only where the surrounding prompt format intentionally accepts
     * raw text, such as AGENTS.md instruction bodies.
     */
    public actual fun rawText(value: String): Unit {
        content.append(value)
    }

    /** Adds one XML tag and its nested prompt content. */
    public actual fun tag(
        name: String,
        attributes: Map<String, String>,
        block: PromptXmlBuilder.() -> Unit,
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

    private fun appendTag(value: String) {
        if (indented && content.isNotEmpty() && content.last() != '\n') {
            content.append('\n')
        }
        content.append(value)
        containsTag = true
    }
}

/** Builds XML-shaped prompt content. */
public actual fun promptXml(
    indented: Boolean,
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
