plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-storage-clean-models"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
