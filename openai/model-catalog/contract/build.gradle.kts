plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-models"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
