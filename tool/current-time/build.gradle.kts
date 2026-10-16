plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":openai-models"))
                api(project(":tool-core"))
                api(libs.kotlinx.schema.json)
            }
        }
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
            implementation(project(":tool-core"))
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
