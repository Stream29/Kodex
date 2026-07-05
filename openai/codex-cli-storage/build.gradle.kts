plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-models"))
            api(libs.kotlinx.io.core)
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
