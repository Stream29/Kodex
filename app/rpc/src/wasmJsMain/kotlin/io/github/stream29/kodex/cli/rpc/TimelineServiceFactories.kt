package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.rpc.contract.IndexTimelineRpc
import io.github.stream29.kodex.rpc.contract.SettingsTimelineRpc
import io.github.stream29.kodex.rpc.contract.TimestampTimelineRpc
import io.github.stream29.kodex.rpc.contract.TokenCountTimelineRpc
import io.github.stream29.kodex.rpc.contract.UnstableTimelineRpc
import io.github.stream29.kodex.rpc.contract.WorkTimelineRpc
import kotlinx.rpc.RpcClient
import kotlinx.rpc.withService

internal actual fun createIndexTimelineClient(client: RpcClient): IndexTimelineRpc = IndexClient(client.withService())
internal actual fun createWorkTimelineClient(client: RpcClient): WorkTimelineRpc = WorkClient(client.withService())
internal actual fun createSettingsTimelineClient(client: RpcClient): SettingsTimelineRpc = SettingsClient(client.withService())
internal actual fun createTimestampTimelineClient(client: RpcClient): TimestampTimelineRpc = TimestampClient(client.withService())
internal actual fun createTokenCountTimelineClient(client: RpcClient): TokenCountTimelineRpc = TokenCountClient(client.withService())
internal actual fun createUnstableTimelineClient(client: RpcClient): UnstableTimelineRpc = UnstableClient(client.withService())

// Kotlin 2.4.20 production Wasm needs concrete interface roots for these associated descriptors.
// Keep the generic TimelineRpc port; these delegates create no RPC or subscriptions.
private class IndexClient(delegate: IndexTimelineRpc) : IndexTimelineRpc by delegate
private class WorkClient(delegate: WorkTimelineRpc) : WorkTimelineRpc by delegate
private class SettingsClient(delegate: SettingsTimelineRpc) : SettingsTimelineRpc by delegate
private class TimestampClient(delegate: TimestampTimelineRpc) : TimestampTimelineRpc by delegate
private class TokenCountClient(delegate: TokenCountTimelineRpc) : TokenCountTimelineRpc by delegate
private class UnstableClient(delegate: UnstableTimelineRpc) : UnstableTimelineRpc by delegate
