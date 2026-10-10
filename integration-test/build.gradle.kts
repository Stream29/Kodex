plugins {
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
            implementation(project(":agent-context-impl-prefix-render"))
            implementation(project(":agent-runtime-impl-decorator-compact"))
            implementation(project(":agent-runtime-impl-composition"))
            implementation(project(":agent-session-impl-filesystem"))
            implementation(project(":agent-state-impl-state"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-spec-contract"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-storage-impl-in-memory"))
            implementation(project(":mcp-spec-contract"))
            implementation(project(":openai-impl-client"))
            implementation(project(":openai-spec-client"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-impl-codex-cli-storage"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":openai-impl-model-catalog"))
            implementation(project(":tool-impl-image-generation"))
            implementation(project(":tool-impl-request-user-input"))
            implementation(project(":tool-impl-view-image"))
            implementation(project(":tool-impl-web-run"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":utils-os-environment-spec"))
            implementation(project(":utils-shell-client-impl"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.core)
        }
        nativeTest.dependencies {
            implementation(libs.mosaic.runtime)
        }
        jvmTest.dependencies {
            implementation(project(":app-impl-application"))
            implementation(project(":app-impl-view"))
            implementation(project(":app-impl-rpc"))
            implementation(project(":app-impl-session"))
            implementation(project(":app-spec-application"))
            implementation(project(":app-spec-session"))
            implementation(project(":app-component-new-session-impl-viewmodel"))
            implementation(project(":app-component-session-catalog-spec"))
            implementation(project(":app-component-session-catalog-impl-viewmodel"))
            implementation(project(":app-component-settings-spec"))
            implementation(project(":app-settings-impl-filesystem"))
            implementation(project(":app-test-support-rpc"))
            implementation(project(":rpc-impl-client"))
            implementation(project(":rpc-impl-in-memory"))
            implementation(project(":utils-rpc-exception-spec"))
            implementation(project(":rpc-impl-server"))
            implementation(project(":app-component-history-impl-view"))
            implementation(project(":mcp-impl-composition"))
            implementation(project(":utils-patch-spec"))
            implementation(project(":utils-terminal-text-spec"))
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
