plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-state-contract"))
            api(project(":agent-context-prefix-contract"))
            implementation(project(":agent-context-collaboration-render"))
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":openai-client-contract"))
            implementation(project(":openai-json-codec"))
            implementation(project(":tool-impl-apply-patch"))
            implementation(project(":tool-impl-current-time"))
            implementation(project(":tool-impl-get-context-remaining"))
            implementation(project(":tool-impl-multi-agent"))
            implementation(project(":tool-impl-plan"))
            implementation(project(":tool-impl-request-user-input"))
            implementation(project(":tool-impl-unified-exec"))
            implementation(project(":tool-impl-web-run"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-context-prefix-render"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":openai-client-test"))
            implementation(project(":tool-tool-search"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
