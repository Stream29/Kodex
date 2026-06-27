package io.github.stream29.codex.lite.tool.contract

import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload

/**
 * Tool-call output payload shared by `function_call_output` and
 * `custom_tool_call_output` in the OpenAI protocol model.
 */
public typealias ToolCallResult = FunctionCallOutputPayload
