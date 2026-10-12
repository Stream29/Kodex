plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonMain.dependencies {
            api(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-os-environment-spec"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
