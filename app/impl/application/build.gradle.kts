plugins {
    id("kodex.kmp-viewmodel")
    // JVM boundary tests mount the real root renderer; its @Composable lambdas
    // require compiler lowering, although production ownership stays UI-free.
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        jvmTest.dependencies {
            implementation(project(":app-impl-view"))
            implementation(libs.mosaic.testing)
        }
        commonMain.dependencies {
            // Compiler-only runtime symbols for lowering JVM test lambdas.
            // Production sources do not import or own Mosaic widget state.
            compileOnly(libs.mosaic.runtime)
            implementation(project(":app-component-session-settings-impl-viewmodel"))
            implementation(project(":app-component-working-directory-impl-viewmodel"))
            implementation(project(":app-component-session-rename-impl-viewmodel"))
            implementation(project(":app-component-session-delete-impl-viewmodel"))
            implementation(project(":app-component-openai-login-impl-viewmodel"))
            implementation(project(":app-migration-impl"))
            implementation(project(":rpc-impl-server"))
            implementation(project(":rpc-impl-in-memory"))
            implementation(project(":app-impl-rpc"))
            implementation(project(":hook-impl-notification"))
            implementation(project(":app-spec-application"))
            implementation(project(":app-spec-session"))
            implementation(project(":app-component-session-catalog-spec"))
            implementation(project(":app-component-session-catalog-impl-viewmodel"))
            implementation(project(":app-component-settings-spec"))
            implementation(project(":app-component-history-impl-viewmodel"))
            implementation(project(":app-component-new-session-impl-viewmodel"))
            implementation(project(":app-impl-session"))
            implementation(project(":mcp-spec-contract"))
            implementation(project(":openai-spec-account-usage"))
            implementation(project(":openai-spec-models"))
            implementation(project(":app-settings-impl-filesystem"))
            implementation(project(":app-component-settings-impl-viewmodel"))
            implementation(project(":app-component-path-picker-impl-viewmodel"))
            implementation(project(":openai-impl-client"))
            implementation(project(":utils-kodex-home-spec"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":utils-logging-impl"))
            implementation(project(":utils-os-environment-spec"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":app-test-support-rpc"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":openai-impl-client-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
