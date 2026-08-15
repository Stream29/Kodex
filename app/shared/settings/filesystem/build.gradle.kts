plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-shared-settings-contract"))
            api(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kaml)
        }
    }
}
