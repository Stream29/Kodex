package io.github.stream29.kodex.rpc.client

import kotlinx.serialization.modules.SerializersModule

/** Original RPC interface/enum serializers, explicitly available to runtime KType lookup on Wasm. */
public expect val RpcWireSerializers: SerializersModule
