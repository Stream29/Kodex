plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":agent-context-settings"))
            api(project(":agent-storage-core"))
            api(project(":mcp"))
            api(project(":openai-client"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-context-prefix"))
            api(project(":tool-tool-search"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-current-time"))
            implementation(project(":tool-image-generation"))
            implementation(project(":tool-view-image"))
            implementation(project(":tool-get-context-remaining"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-request-user-input"))
            implementation(project(":tool-multi-agent"))
            implementation(project(":tool-unified-exec"))
            implementation(project(":tool-web-run"))
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":agent-context-prefix"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
