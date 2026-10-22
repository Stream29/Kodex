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
public expect class PromptXmlBuilder {
    /** Appends XML-escaped text content. */
    public fun text(value: String)

    /** Appends XML-escaped text content. */
    public operator fun String.unaryPlus()

    /**
     * Appends trusted prompt content without XML escaping.
     *
     * Use this only where the surrounding prompt format intentionally accepts
     * raw text, such as AGENTS.md instruction bodies.
     */
    public fun rawText(value: String)

    /** Adds one XML tag and its nested prompt content. */
    public fun tag(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        block: PromptXmlBuilder.() -> Unit = {},
    )
}

/** Builds XML-shaped prompt content. */
public expect fun promptXml(
    indented: Boolean = true,
    block: PromptXmlBuilder.() -> Unit,
): String
