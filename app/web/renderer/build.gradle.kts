plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-tests")
    id("kodex.kmp-web")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-application-viewmodel"))
            api(project(":app-component-application-view"))
            api(project(":app-component-history-view"))
            api(project(":app-component-settings-view"))
            api(compose.runtime)
        }
        wasmJsMain.dependencies {
            implementation(project(":app-web-theme"))
            implementation(project(":app-component-path-picker-view"))
            implementation(project(":app-component-session-catalog-view"))
            implementation(libs.multiplatform.markdown.core)
            implementation(libs.multiplatform.markdown.m3)
            implementation(libs.navigation.event)
            implementation(libs.navigation.event.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        wasmJsTest.dependencies {
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":agent-state-contract"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
