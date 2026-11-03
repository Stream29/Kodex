package io.github.stream29.kodex.rpc.client

import de.infix.testBalloon.framework.core.testSuite
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
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.FrontendErrorAdmission
import io.github.stream29.kodex.rpc.models.DirectoryQuery
import io.github.stream29.kodex.rpc.models.DirectoryQueryFailure
import io.github.stream29.kodex.rpc.models.DirectoryValidationResult
import io.github.stream29.kodex.rpc.models.DirectoryListResult
import io.github.stream29.kodex.rpc.models.BackendChildDirectory
import io.github.stream29.kodex.rpc.models.ContextPathQuery
import io.github.stream29.kodex.agentcontext.contract.ContextPathComparison
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluationFailure
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf
import kotlin.test.assertEquals

val rpcWireSerializersTest by testSuite {
    val originalJson = Json { encodeDefaults = true }
    test("runtime lookup retains every interface and enum transport root") {
        checkLookup<SettingsAuthenticationState>(SettingsAuthenticationState.serializer())
        checkLookup<SettingsAccountUsageState>(SettingsAccountUsageState.serializer())
        checkLookup<Notification>(Notification.serializer())
        checkLookup<OAuthTarget>(OAuthTarget.serializer())
        checkLookup<OAuthAuthorization>(OAuthAuthorization.serializer())
        checkLookup<FrontendErrorAdmission>(FrontendErrorAdmission.serializer())
        checkLookup<DirectoryQuery>(DirectoryQuery.serializer())
        checkLookup<DirectoryQueryFailure>(DirectoryQueryFailure.serializer())
        checkLookup<DirectoryValidationResult>(DirectoryValidationResult.serializer())
        checkLookup<DirectoryListResult>(DirectoryListResult.serializer())
        checkLookup<BackendChildDirectory>(BackendChildDirectory.serializer())
        checkLookup<ContextPathQuery>(ContextPathQuery.serializer())
        checkLookup<ContextPathComparison>(ContextPathComparison.serializer())
        checkLookup<ContextPathEvaluation>(ContextPathEvaluation.serializer())
        checkLookup<ContextPathEvaluationFailure>(ContextPathEvaluationFailure.serializer())
        checkLookup<AgentStateValue>(AgentStateValue.serializer())
        checkLookup<ContentItem>(ContentItem.serializer())
        checkLookup<ResponsesStreamEvent>(ResponsesStreamEvent.serializer())
        checkLookup<StableCleanEvent.CompletedTool>(StableCleanEvent.CompletedTool.serializer())
        checkLookup<StableIndexEvent.Steerable>(StableIndexEvent.Steerable.serializer())
        checkLookup<CleanIndexEntry>(CleanIndexEntry.serializer())
        checkLookup<StableWorkEvent>(StableWorkEvent.serializer())
        checkLookup<UnstableCleanEvent>(UnstableCleanEvent.serializer())
        checkLookup<KodexAuthSource>(KodexAuthSource.serializer())
        checkLookup<CodexRateLimitResetOutcome>(CodexRateLimitResetOutcome.serializer())
    }

    test("runtime interface lookup preserves the original authentication wire bytes") {
        val json = Json { serializersModule = RpcWireSerializers; encodeDefaults = true }
        val value = SettingsAuthenticationState.Authenticated()
        val runtime = RpcWireSerializers.serializer(typeOf<SettingsAuthenticationState>())
        val encoded = json.encodeToString(runtime, value)
        assertEquals(originalJson.encodeToString(SettingsAuthenticationState.serializer(), value), encoded)
        assertEquals(value, json.decodeFromString(runtime, encoded))
    }

    test("runtime nested collection lookup preserves original content values") {
        val json = Json { serializersModule = RpcWireSerializers }
        val runtime = RpcWireSerializers.serializer(typeOf<List<Pair<Int, ContentItem?>>>())
        val value: List<Pair<Int, ContentItem?>> = listOf(7 to ContentItem.InputText("你好"), 8 to null)
        val encoded = json.encodeToString(runtime, value)
        assertEquals(json.encodeToString(value), encoded)
        assertEquals(value, json.decodeFromString(runtime, encoded))
    }

    test("backend path strings round trip without frontend path interpretation") {
        val json = Json { serializersModule = RpcWireSerializers }
        val runtime = RpcWireSerializers.serializer(typeOf<DirectoryQuery>())
        for (path in listOf("""C:\Projects\中文 space""", """\\server\share\dir""", "C:relative", "../child")) {
            val value = DirectoryQuery(path, """C:\launch""")
            val encoded = json.encodeToString(runtime, value)
            assertEquals(json.encodeToString(value), encoded)
            assertEquals(value, json.decodeFromString(runtime, encoded))
        }
    }

    test("directory and context results preserve typed failures and ordered comparison rows") {
        val json = Json { serializersModule = RpcWireSerializers; encodeDefaults = true }
        val listing: DirectoryListResult = DirectoryListResult.Directories(
            """C:\launch""", """C:\""", listOf(BackendChildDirectory("""C:\launch\child""", "child")),
        )
        val failed: DirectoryListResult = DirectoryListResult.Failure(DirectoryQueryFailure.FileSystem)
        val validation: DirectoryValidationResult =
            DirectoryValidationResult.Failure(DirectoryQueryFailure.NotDirectory, "/parent")
        val original = listOf(ContextPathComparison(null, false), ContextPathComparison("/home/.agents", true))
        val evaluated: ContextPathEvaluation = ContextPathEvaluation.Evaluated(
            ContextPathComparison("/home/custom", false), original,
        )
        assertEquals(listing, json.decodeFromString<DirectoryListResult>(json.encodeToString(listing)))
        assertEquals(failed, json.decodeFromString<DirectoryListResult>(json.encodeToString(failed)))
        assertEquals(validation, json.decodeFromString<DirectoryValidationResult>(json.encodeToString(validation)))
        val runtime = RpcWireSerializers.serializer(typeOf<ContextPathEvaluation>())
        assertEquals(evaluated, json.decodeFromString(runtime, json.encodeToString(runtime, evaluated)))
        val query = ContextPathQuery("~/custom", listOf("relative", "~/.agents"), "/launch")
        assertEquals(query, json.decodeFromString<ContextPathQuery>(json.encodeToString(query)))
    }
}

private inline fun <reified T> checkLookup(original: KSerializer<T>) {
    assertEquals(original.descriptor.serialName, RpcWireSerializers.serializer(typeOf<T>()).descriptor.serialName)
}
