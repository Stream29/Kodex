plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-client")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        nativeMain.dependencies {
            implementation(project(":utils-process-client"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
