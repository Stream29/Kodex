plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":rpc-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(project(":utils-kotlinx-io-coroutines-system"))
        }
        commonMain.dependencies {
            implementation(libs.kaml)
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
