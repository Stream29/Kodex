package io.github.stream29.codex.lite.tool.requestuserinput

import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.schema.json.PropertyBuilder

public val RequestUserInputParametersSchema: ObjectPropertyDefinition =
    PropertyBuilder().obj {
        additionalProperties = false
        property("questions") {
            required = true
            array {
                description = "Questions to show the user. Prefer 1 and do not exceed 3"
                ofObject {
                    additionalProperties = false
                    property("id") {
                        required = true
                        string { description = "Stable identifier for mapping answers (snake_case)." }
                    }
                    property("header") {
                        required = true
                        string { description = "Short header label shown in the UI (12 or fewer chars)." }
                    }
                    property("question") {
                        required = true
                        string { description = "Single-sentence prompt shown to the user." }
                    }
                    property("options") {
                        required = true
                        array {
                            description =
                                "Provide 2-3 mutually exclusive choices. Put the recommended option first and suffix its label with \"(Recommended)\". Do not include an \"Other\" option in this list; the client will add a free-form \"Other\" option automatically."
                            ofObject {
                                additionalProperties = false
                                property("label") {
                                    required = true
                                    string { description = "User-facing label (1-5 words)." }
                                }
                                property("description") {
                                    required = true
                                    string { description = "One short sentence explaining impact/tradeoff if selected." }
                                }
                            }
                        }
                    }
                }
            }
        }
        property("autoResolutionMs") {
            number {
                description =
                    "Optional auto-resolution window in milliseconds, from 60000 to 240000. Include this only when the question is useful but non-blocking and continuing with best judgment is acceptable if the user does not answer; omit it when explicit user input is required before continuing. Use 60000 for lightly helpful context and up to 240000 when the answer would materially unblock better work."
            }
        }
    }
