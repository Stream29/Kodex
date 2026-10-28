plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-state-contract"))
                api(project(":agent-storage-clean-models"))
                api(project(":tool-unified-exec-contract"))
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}
