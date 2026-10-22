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
                api(project(":agent-storage-clean-models"))
                api(project(":app-component-authentication-settings-viewmodel"))
                api(project(":app-component-new-session-defaults-viewmodel"))
                api(project(":app-component-session-title-settings-viewmodel"))
                api(project(":app-component-application-preferences-viewmodel"))
                api(project(":agent-context-settings"))
                api(project(":mcp-contract"))
                api(project(":utils-shell-client"))
                api(libs.kotlinx.serialization.core)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
