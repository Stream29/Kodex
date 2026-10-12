plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-composer-impl-viewmodel"))
            api(project(":app-component-runtime-configuration-impl-viewmodel"))
            api(project(":app-component-history-index-impl-viewmodel"))
            api(project(":app-component-request-user-input-impl-viewmodel"))
            api(project(":app-component-suggest-subagent-task-impl-viewmodel"))
            api(project(":agent-storage-spec-clean-models"))
            api(project(":app-component-history-impl-viewmodel"))
            api(project(":openai-spec-models"))
            api(project(":rpc-spec-models"))
            api(project(":tool-request-user-input"))
            api(project(":tool-multi-agent"))
            api(project(":tool-spec-unified-exec"))
            api(project(":utils-rpc-exception-spec"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
    }
}
