plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
