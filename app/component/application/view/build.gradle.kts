plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-component-view")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-application-viewmodel"))
            api(project(":app-component-session-tab-bar-view"))
            api(project(":app-component-session-sidebar-view"))
            api(project(":app-component-agent-view"))
            api(compose.runtime)
        }
        commonMain.dependencies {
            implementation(project(":app-component-formatting"))
            implementation(project(":app-component-mcp-settings-view"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
