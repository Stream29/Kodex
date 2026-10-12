plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-storage-spec-clean-models"))
                api(project(":app-component-authentication-settings-impl-viewmodel"))
                api(project(":app-component-new-session-defaults-impl-viewmodel"))
                api(project(":app-component-session-title-settings-impl-viewmodel"))
                api(project(":app-component-application-preferences-impl-viewmodel"))
                api(project(":hook-spec-notification"))
                api(project(":agent-context-spec-contract"))
                api(project(":mcp-spec-contract"))
                api(project(":utils-shell-client-spec"))
                api(libs.kotlinx.serialization.core)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
