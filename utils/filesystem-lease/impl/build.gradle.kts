plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-filesystem-lease-spec"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-os-environment-spec"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
