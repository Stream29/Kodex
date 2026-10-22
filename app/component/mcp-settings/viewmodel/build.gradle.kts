plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":mcp-contract"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
