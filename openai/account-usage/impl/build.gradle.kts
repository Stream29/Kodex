plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-shared-auth-contract"))
            api(project(":openai-account-usage-contract"))
            api(project(":openai-client-contract"))
            implementation(project(":openai-models"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
