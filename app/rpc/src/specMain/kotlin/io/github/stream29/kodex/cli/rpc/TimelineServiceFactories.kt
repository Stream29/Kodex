package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.rpc.contract.IndexTimelineRpc
import io.github.stream29.kodex.rpc.contract.SettingsTimelineRpc
import io.github.stream29.kodex.rpc.contract.TimestampTimelineRpc
import io.github.stream29.kodex.rpc.contract.TokenCountTimelineRpc
import io.github.stream29.kodex.rpc.contract.UnstableTimelineRpc
import io.github.stream29.kodex.rpc.contract.WorkTimelineRpc
import kotlinx.rpc.RpcClient

internal expect fun createIndexTimelineClient(client: RpcClient): IndexTimelineRpc
internal expect fun createWorkTimelineClient(client: RpcClient): WorkTimelineRpc
internal expect fun createSettingsTimelineClient(client: RpcClient): SettingsTimelineRpc
internal expect fun createTimestampTimelineClient(client: RpcClient): TimestampTimelineRpc
internal expect fun createTokenCountTimelineClient(client: RpcClient): TokenCountTimelineRpc
internal expect fun createUnstableTimelineClient(client: RpcClient): UnstableTimelineRpc
