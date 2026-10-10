plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-spec-multi-agent"))
            api(project(":openai-spec-models"))
            api(libs.kotlinx.schema.json)
        }
        commonTest.dependencies {
            implementation(project(":openai-spec-json-codec"))
        }
    }
}
