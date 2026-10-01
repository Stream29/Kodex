plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-spec-decorator-tool"))
            api(project(":agent-runtime-spec-contract"))
            api(project(":hook-contract"))
            api(project(":tool-spec-contract"))
            api(project(":tool-impl-tool-search"))
            api(libs.kotlin.logging)
            api(libs.kotlinx.coroutines.core)
            implementation(project(":hook-tool-utils"))
            implementation(project(":utils-logging-impl"))
        }
        commonTest.dependencies {
            implementation(project(":agent-runtime-impl-decorator-turn-hook"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-state-tool"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":mcp-spec-contract"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":tool-impl-apply-patch"))
            implementation(project(":tool-impl-plan"))
            implementation(project(":tool-impl-unified-exec"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":utils-shell-client-impl"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
