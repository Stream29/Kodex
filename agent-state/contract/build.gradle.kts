plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(project(":agent-storage-core"))
                api(project(":agent-storage-clean-models"))
                api(project(":openai-models"))
            }
        }
    }
}
