plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-component-view")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-agent-contract"))
            api(project(":app-component-runtime-configuration-view"))
            api(project(":app-component-composer-view"))
            api(project(":app-component-history-view"))
            api(project(":app-component-request-user-input-view"))
            api(project(":app-component-suggest-subagent-task-view"))
            api(compose.runtime)
        }
    }
}
