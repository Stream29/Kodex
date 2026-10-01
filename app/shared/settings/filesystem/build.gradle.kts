plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-shared-settings-contract"))
            api(project(":rpc-spec-models"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kaml)
        }
    }
}
