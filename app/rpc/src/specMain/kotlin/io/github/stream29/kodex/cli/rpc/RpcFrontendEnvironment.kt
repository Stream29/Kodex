package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.contextsourcesettings.ContextSourcePathPolicy
/** Environment adapters borrowed by an editor; Context evaluates on its bound backend instance. */
public interface RpcFrontendEnvironment {
    public val contextPaths: ContextSourcePathPolicy
}
