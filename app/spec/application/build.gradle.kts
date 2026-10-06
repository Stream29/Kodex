plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-working-directory-spec"))
            api(project(":app-component-session-rename-spec"))
            api(project(":app-component-session-delete-spec"))
            api(project(":app-component-openai-login-spec"))
            api(project(":app-component-agent-spec"))
            api(project(":tool-spec-multi-agent"))
            api(project(":app-component-path-picker-spec"))
            api(project(":app-component-session-catalog-spec"))
            api(project(":app-spec-session"))
            api(project(":app-component-new-session-spec"))
            api(project(":app-component-settings-spec"))
            api(project(":app-component-session-sidebar-spec"))
            api(project(":app-component-application-preferences-spec"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
    }
}
