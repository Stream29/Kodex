plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-spec-contract"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-read-write-mutex-impl"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
