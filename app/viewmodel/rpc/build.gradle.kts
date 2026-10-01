plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":rpc-client"))
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
            implementation(project(":rpc-server"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":openai-impl-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.cio)
        }
    }
}
