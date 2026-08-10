plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-request-user-input-contract"))
            api(project(":openai-models"))
            api(libs.kotlinx.schema.json)
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
        }
    }
}
