plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":hook-contract"))
            api(project(":tool-contract"))
            api(project(":tool-tool-search-impl"))
            api(libs.kotlin.logging)
            api(libs.kotlinx.coroutines.core)
            implementation(project(":hook-tool-utils"))
            implementation(project(":utils-logging"))
        }
        commonTest.dependencies {
            implementation(project(":agent-runtime-decorator-turn-hook"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-state-tool"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":mcp-contract"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-unified-exec-impl"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-shell-client"))
            implementation(libs.kotlinx.io.core)
        }
    }
}
