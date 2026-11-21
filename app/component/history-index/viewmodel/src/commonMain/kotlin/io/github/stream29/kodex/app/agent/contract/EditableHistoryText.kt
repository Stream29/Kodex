package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.openai.ContentItem

public actual fun StableUserMessage.editableHistoryText(): String? = buildString {
    for (segment in content) when (segment) {
        is ContentItem.InputText -> append(segment.text)
        is ContentItem.OutputText -> append(segment.text)
        is ContentItem.InputImage -> return null
    }
}
