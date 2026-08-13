plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":app-contract-agent"))
            implementation(project(":app-contract-session"))
            implementation(project(":openai-models"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":app-viewmodel-agent"))
            implementation(project(":app-viewmodel-history"))
            implementation(project(":app-viewmodel-session"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
