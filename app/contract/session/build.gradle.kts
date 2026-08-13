plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-agent"))
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
