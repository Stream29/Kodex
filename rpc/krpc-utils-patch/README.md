# kRPC 0.10.3 utils compatibility patch

This module reproduces the small `kotlinx-rpc-utils` 0.10.3 API for the CLI's
JVM and Native targets. It replaces **only** that dependency in the Kodex build.
The source is derived from
[kotlinx-rpc 0.10.3](https://github.com/Kotlin/kotlinx-rpc/tree/0.10.3/utils)
and remains under its Apache 2.0 license. The corresponding upstream fix is
prepared in `~/ACodeSpace/fork/kotlinx-rpc`.
The bundled [LICENSE](LICENSE) is copied from the upstream repository.

The only semantic change is that `SynchronizedHashMap` copies `entries`,
`keys`, and `values` while holding its lock. kRPC may traverse these collections
after the getter returns; exposing a live `HashMap` view causes Native
concurrent-modification failures during cancellation.
The replacement Native KLIB deliberately retains the upstream
`org.jetbrains.kotlinx:utils` unique name required by precompiled kRPC 0.10.3
components.

This is a temporary dependency patch, not a new RPC contract or protocol. Remove
it once an upstream release with the fix is validated on the same targets.
