package io.github.stream29.kodex.app.webrenderersmoke

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
import io.github.stream29.kodex.rpc.client.RpcWireSerializers
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf

private val smokeJson = Json { serializersModule = RpcWireSerializers; encodeDefaults = true }

/** Uses runtime KType lookup, as kRPC does, in the actual optimized browser executable. */
internal fun checkRpcSerialization(): String = try {
    val roots = listOf(
        typeOf<SettingsAuthenticationState>(), typeOf<SettingsAccountUsageState>(),
        typeOf<Notification>(), typeOf<OAuthTarget>(), typeOf<AgentStateValue>(),
        typeOf<ContentItem>(), typeOf<ResponsesStreamEvent>(),
        typeOf<StableCleanEvent.CompletedTool>(), typeOf<StableIndexEvent.Steerable>(),
        typeOf<CleanIndexEntry>(), typeOf<StableWorkEvent>(), typeOf<UnstableCleanEvent>(),
        typeOf<KodexAuthSource>(), typeOf<CodexRateLimitResetOutcome>(),
        typeOf<List<Pair<Int, ContentItem?>>>(),
    )
    roots.forEach { check(RpcWireSerializers.serializer(it).descriptor.serialName.isNotBlank()) }
    val serializer = RpcWireSerializers.serializer(typeOf<SettingsAuthenticationState>())
    val value = SettingsAuthenticationState.Authenticated()
    val encoded = smokeJson.encodeToString(serializer, value)
    check(encoded == """{"type":"io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState.Authenticated","accountId":null,"planType":null,"email":null}""")
    check(smokeJson.decodeFromString(serializer, encoded) == value)
    "All fourteen RPC roots and nested collection serializers resolved; original auth wire round-tripped."
} catch (failure: Exception) {
    "RPC serialization failed: ${failure.message}"
}
