plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-settings-spec-persistence"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kaml)
            implementation(libs.kotlinx.serialization.core)
        }
    }
}
