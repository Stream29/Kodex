package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.rpc.contract.IndexTimelineRpc
import io.github.stream29.kodex.rpc.contract.SettingsTimelineRpc
import io.github.stream29.kodex.rpc.contract.TimestampTimelineRpc
import io.github.stream29.kodex.rpc.contract.TokenCountTimelineRpc
import io.github.stream29.kodex.rpc.contract.UnstableTimelineRpc
import io.github.stream29.kodex.rpc.contract.WorkTimelineRpc
import kotlinx.rpc.RpcClient
import kotlinx.rpc.withService

internal actual fun createIndexTimelineClient(client: RpcClient): IndexTimelineRpc = client.withService()
internal actual fun createWorkTimelineClient(client: RpcClient): WorkTimelineRpc = client.withService()
internal actual fun createSettingsTimelineClient(client: RpcClient): SettingsTimelineRpc = client.withService()
internal actual fun createTimestampTimelineClient(client: RpcClient): TimestampTimelineRpc = client.withService()
internal actual fun createTokenCountTimelineClient(client: RpcClient): TokenCountTimelineRpc = client.withService()
internal actual fun createUnstableTimelineClient(client: RpcClient): UnstableTimelineRpc = client.withService()
