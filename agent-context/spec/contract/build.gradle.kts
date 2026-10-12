plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(project(":utils-shell-client-spec"))
                api(libs.kotlinx.io.core)
            }
        }
    }
}
