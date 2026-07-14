plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":openai-models"))
            implementation(project(":openai-json-codec"))
        }
    }
}
