plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-spec-models"))
            api(project(":tool-spec-tool-search"))
            api(libs.kotlinx.schema.json)
            implementation(project(":utils-search-index-impl"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
