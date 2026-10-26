package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

// Kotlin 2.4.20 production Wasm can lose interface serializer lookup via associated objects.
// Register transport roots, including collection elements, using their unchanged serializers.
public actual val RpcWireSerializers: SerializersModule = SerializersModule {
    contextual(SettingsAuthenticationState::class, SettingsAuthenticationState.serializer())
    contextual(SettingsAccountUsageState::class, SettingsAccountUsageState.serializer())
    contextual(Notification::class, Notification.serializer())
    contextual(OAuthTarget::class, OAuthTarget.serializer())
    contextual(AgentStateValue::class, AgentStateValue.serializer())
    contextual(ContentItem::class, ContentItem.serializer())
    contextual(ResponsesStreamEvent::class, ResponsesStreamEvent.serializer())
    contextual(StableCleanEvent.CompletedTool::class, StableCleanEvent.CompletedTool.serializer())
    contextual(StableIndexEvent.Steerable::class, StableIndexEvent.Steerable.serializer())
    contextual(CleanIndexEntry::class, CleanIndexEntry.serializer())
    contextual(StableWorkEvent::class, StableWorkEvent.serializer())
    contextual(UnstableCleanEvent::class, UnstableCleanEvent.serializer())
    contextual(KodexAuthSource::class, KodexAuthSource.serializer())
    contextual(CodexRateLimitResetOutcome::class, CodexRateLimitResetOutcome.serializer())
}
