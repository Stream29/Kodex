plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.schema.json)
            }
        }
        commonMain.dependencies {
            api(project(":openai-client"))
            api(project(":openai-models"))
            api(project(":tool-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
        }
    }
}
