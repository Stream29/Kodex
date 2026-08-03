plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-shared-session"))
            api(project(":app-shared-settings-contract"))
            implementation(project(":openai-models"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
