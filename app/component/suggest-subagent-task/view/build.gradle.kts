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
            api(project(":app-component-suggest-subagent-task-viewmodel"))
            api(project(":app-component-runtime-configuration-view"))
            api(compose.runtime)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
