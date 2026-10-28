plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-composer-viewmodel"))
            api(project(":app-component-runtime-configuration-viewmodel"))
            api(project(":app-component-history-index-viewmodel"))
            api(project(":app-component-request-user-input-viewmodel"))
            api(project(":app-component-suggest-subagent-task-viewmodel"))
            api(project(":agent-storage-clean-models"))
            api(project(":app-component-history-viewmodel"))
            api(project(":openai-models"))
            api(project(":rpc-models"))
            api(project(":tool-request-user-input"))
            api(project(":tool-multi-agent"))
            api(project(":tool-unified-exec-contract"))
            api(project(":utils-rpc-exception"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
    }
}
