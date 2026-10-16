plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-host")
    kotlin("plugin.compose")
    id("kodex.kmp-tests")
}

val patchRendererPerformanceProbeEnabled = providers
    .environmentVariable("KODEX_PATCH_RENDERER_PERFORMANCE_PROBE")
    .map { value -> value == "1" }
    .orElse(false)
val patchRendererPerformanceProbeRepetitions = providers
    .environmentVariable("KODEX_PATCH_RENDERER_PROBE_REPETITIONS")
    .orElse("3")

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":agent-context-prefix"))
            implementation(project(":agent-runtime-decorator-compact"))
            implementation(project(":agent-runtime-composition"))
            implementation(project(":agent-runtime-steer"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":agent-state-core"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":mcp"))
            implementation(project(":openai-client"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-json-codec"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":tool-image-generation"))
            implementation(project(":tool-request-user-input"))
            implementation(project(":tool-view-image"))
            implementation(project(":tool-web-run"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":utils-os-environment"))
            implementation(project(":utils-shell-client-system"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.core)
        }
        nativeTest.dependencies {
            implementation(libs.mosaic.runtime)
        }
        jvmTest.dependencies {
            implementation(project(":app-application"))
            implementation(project(":app-view"))
            implementation(project(":app-rpc"))
            implementation(project(":app-session"))
            implementation(project(":app-session-contract"))
            implementation(project(":app-component-new-session-viewmodel"))
            implementation(project(":app-component-session-catalog-viewmodel"))
            implementation(project(":app-component-settings-viewmodel"))
            implementation(project(":app-settings-filesystem"))
            implementation(project(":app-test-support-rpc"))
            implementation(project(":rpc-client"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":utils-rpc-exception"))
            implementation(project(":rpc-server"))
            implementation(project(":app-component-history-view"))
            implementation(project(":mcp"))
            implementation(project(":utils-patch"))
            implementation(project(":utils-terminal-text"))
            implementation(libs.mosaic.runtime)
            implementation(libs.mosaic.testing)
            implementation(libs.kotlinx.schema.json)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.mcp.kotlin.sdk.server)
        }
    }
}

tasks.named("jvmTest") {
    inputs.property("patchRendererPerformanceProbeEnabled", patchRendererPerformanceProbeEnabled)
    inputs.property("patchRendererPerformanceProbeRepetitions", patchRendererPerformanceProbeRepetitions)
    if (patchRendererPerformanceProbeEnabled.get()) {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Patch renderer performance probe is enabled.") { true }
    }
}
