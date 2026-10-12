plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-state-spec-contract"))
                api(project(":agent-storage-spec-clean-models"))
                api(project(":tool-spec-unified-exec"))
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}
