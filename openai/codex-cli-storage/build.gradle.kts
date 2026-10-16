plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":openai-models"))
                api(libs.kotlinx.serialization.json)
                api(libs.tomlkt)
            }
        }
        commonMain.dependencies {
            api(project(":utils-kotlinx-io-coroutines-system"))
            api(libs.kotlinx.io.core)
            api(libs.tomlkt)
            implementation(project(":openai-json-codec"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
