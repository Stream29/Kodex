plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-composer-spec"))
            api(project(":app-component-runtime-configuration-spec"))
            api(project(":app-component-history-index-spec"))
            api(project(":app-component-request-user-input-spec"))
            api(project(":app-component-suggest-subagent-task-spec"))
            api(project(":agent-storage-spec-clean-models"))
            api(project(":app-component-history-spec"))
            api(project(":openai-spec-models"))
            api(project(":rpc-spec-models"))
            api(project(":tool-spec-request-user-input"))
            api(project(":tool-spec-multi-agent"))
            api(project(":tool-spec-unified-exec"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
    }
}
