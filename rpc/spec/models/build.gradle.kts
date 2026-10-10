plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-spec-clean-models"))
            api(project(":app-component-authentication-settings-spec"))
            api(project(":app-component-new-session-defaults-spec"))
            api(project(":app-component-session-title-settings-spec"))
            api(project(":app-component-application-preferences-spec"))
            api(project(":hook-spec-notification"))
            api(project(":agent-context-spec-contract"))
            api(project(":mcp-spec-contract"))
            api(project(":utils-shell-client-spec"))
            api(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
