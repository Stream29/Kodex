plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-component-view")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-agent-contract"))
            api(project(":app-component-composer-view"))
            api(project(":app-component-history-view"))
            api(compose.runtime)
        }
    }
}
