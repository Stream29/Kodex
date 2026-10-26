plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-storage-filesystem"))
                api(project(":utils-kotlinx-io-coroutines-system"))
            }
        }
    }
}
