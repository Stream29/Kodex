plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-kotlinx-io-serialization-spec"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.schema.json)
        }
        commonTest.dependencies {
            implementation(project(":openai-spec-json-codec"))
        }
    }
}
