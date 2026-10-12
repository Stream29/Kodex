plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":utils-images-spec"))
            }
        }
        commonMain.dependencies {
            api(project(":utils-kotlinx-io-coroutines-spec"))
            api(libs.kotlinx.io.core)
        }
    }
}
