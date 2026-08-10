plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-models"))
            api(project(":tool-tool-search-contract"))
            api(libs.kotlinx.schema.json)
            implementation(project(":utils-search-index"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
