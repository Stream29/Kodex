plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-kotlinx-io-coroutines"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
