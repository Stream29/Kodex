plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-schedule-contract"))
                api(project(":utils-sqlite"))
                api(libs.kotlinx.coroutines.core)
            }
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-sqlite"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
