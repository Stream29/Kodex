plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-spec-account-usage"))
            api(project(":openai-spec-client"))
            implementation(project(":openai-spec-models"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
