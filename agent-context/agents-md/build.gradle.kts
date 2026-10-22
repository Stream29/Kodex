plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.io.core)
                api(project(":agent-context-settings"))
                api(project(":utils-kotlinx-io-coroutines-system"))
            }
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-system"))
        }
    }
}
