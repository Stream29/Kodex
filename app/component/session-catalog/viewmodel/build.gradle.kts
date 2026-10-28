plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-rpc-protocol"))
            api(project(":app-component-session-delete-viewmodel"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
        }
        commonMain.dependencies {
            implementation(project(":app-component-session-delete-viewmodel"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
