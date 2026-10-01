plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-openai-login-spec"))
            implementation(project(":app-component-openai-login-impl-viewmodel"))
            implementation(project(":app-component-session-catalog-impl-viewmodel"))
            api(project(":rpc-impl-client"))
            api(project(":app-viewmodel-history"))
            api(project(":app-contract-agent"))
            api(project(":agent-state-contract"))
            implementation(project(":app-viewmodel-agent"))
            api(project(":app-viewmodel-settings"))
            api(project(":app-shared-settings-filesystem"))
            implementation(libs.kotlin.logging)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.core)
        }
        commonTest.dependencies {
            implementation(project(":rpc-impl-server"))
            implementation(project(":rpc-impl-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.cio)
        }
    }
}
