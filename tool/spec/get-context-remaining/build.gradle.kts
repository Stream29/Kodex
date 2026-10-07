plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-spec-models"))
            api(project(":tool-spec-contract"))
            api(libs.kotlinx.schema.json)
        }
    }
}
