plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":rpc-spec-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            api(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kaml)
            implementation(libs.kotlinx.serialization.core)
        }
    }
}
