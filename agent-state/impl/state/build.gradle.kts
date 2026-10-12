plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-spec-contract"))
            api(project(":agent-context-spec-contract"))
            api(project(":agent-storage-spec-contract-ext"))
            api(project(":mcp-spec-contract"))
            api(project(":openai-client"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-context-prefix"))
            api(project(":tool-tool-search"))
            implementation(project(":tool-tool-search"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-current-time"))
            implementation(project(":tool-impl-image-generation"))
            implementation(project(":tool-impl-view-image"))
            implementation(project(":tool-get-context-remaining"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-request-user-input"))
            implementation(project(":tool-multi-agent"))
            implementation(project(":tool-impl-unified-exec"))
            implementation(project(":tool-web-run"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":utils-coroutines-spec"))
        }
        commonTest.dependencies {
            implementation(project(":agent-context-prefix"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
