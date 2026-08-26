plugins {
    id("kodex.kmp-host")
    kotlin("plugin.compose")
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
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-runtime-decorator-compact"))
            implementation(project(":agent-runtime-impl"))
            implementation(project(":agent-session-filesystem"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-contract"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":hook-contract"))
            implementation(project(":hook-impl"))
            implementation(project(":mcp-contract"))
            implementation(project(":openai-client"))
            implementation(project(":openai-client-contract"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-codex-cli-storage"))
            implementation(project(":openai-json-codec"))
            implementation(project(":openai-model-catalog-impl"))
            implementation(project(":tool-image-generation-impl"))
            implementation(project(":tool-request-user-input-impl"))
            implementation(project(":tool-view-image-impl"))
            implementation(project(":tool-web-run"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
            implementation(project(":utils-shell-client"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(project(":app-view-patch"))
            implementation(project(":mcp-impl"))
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
