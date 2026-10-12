plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":tool-spec-contract"))
            }
        }
        commonMain.dependencies {
            api(project(":utils-kotlinx-io-coroutines-impl"))
            api(project(":utils-patch-impl"))
        }
    }
}
