plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-spec-contract"))
            api(project(":agent-context-spec-contract"))
            api(project(":agent-storage-spec-contract-ext"))
            api(project(":mcp-spec-contract"))
            api(project(":openai-spec-client"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-context-impl-prefix"))
            implementation(project(":agent-context-impl-prefix-render"))
            api(project(":tool-spec-tool-search"))
            implementation(project(":tool-impl-tool-search"))
            implementation(project(":tool-impl-apply-patch"))
            implementation(project(":tool-impl-current-time"))
            implementation(project(":tool-impl-image-generation"))
            implementation(project(":tool-impl-view-image"))
            implementation(project(":tool-impl-get-context-remaining"))
            implementation(project(":tool-impl-plan"))
            implementation(project(":tool-impl-request-user-input"))
            implementation(project(":tool-impl-multi-agent"))
            implementation(project(":tool-impl-unified-exec"))
            implementation(project(":tool-impl-web-run"))
            implementation(project(":openai-spec-json-codec"))
            implementation(project(":utils-coroutines-spec"))
        }
        commonTest.dependencies {
            implementation(project(":agent-context-impl-prefix-render"))
            implementation(project(":agent-storage-impl-in-memory"))
            implementation(project(":agent-storage-impl-filesystem"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
