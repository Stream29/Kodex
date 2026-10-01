plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-spec-web-run"))
            api(project(":openai-spec-client"))
            api(project(":openai-spec-models"))
            api(project(":tool-spec-contract"))
            api(libs.kotlinx.schema.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":openai-impl-client-test"))
            implementation(project(":openai-spec-json-codec"))
        }
    }
}
