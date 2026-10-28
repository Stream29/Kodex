plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":agent-context-settings"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
