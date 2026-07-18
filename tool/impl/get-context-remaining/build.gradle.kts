plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-contract"))
            api(project(":tool-tool-builder"))
            implementation(libs.kotlinx.schema.json)
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
        }
    }
}
