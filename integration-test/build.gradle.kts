plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-host")
    kotlin("plugin.compose")
    id("kodex.kmp-tests")
}

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
            implementation(project(":mcp-contract"))
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
    }
}
