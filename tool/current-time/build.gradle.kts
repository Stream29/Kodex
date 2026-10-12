plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":openai-spec-models"))
                api(project(":tool-spec-contract"))
                api(libs.kotlinx.schema.json)
            }
        }
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
            implementation(project(":tool-builder"))
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
