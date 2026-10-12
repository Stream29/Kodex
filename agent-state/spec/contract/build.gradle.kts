plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(project(":agent-storage-core"))
                api(project(":agent-storage-spec-clean-models"))
                api(project(":openai-spec-models"))
            }
        }
    }
}
