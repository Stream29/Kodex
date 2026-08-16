plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-models"))
            api(project(":utils-kotlinx-io-coroutines"))
            api(libs.kotlinx.io.core)
            api(libs.tomlkt)
            implementation(project(":openai-json-codec"))
        }
        commonTest.dependencies {
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
