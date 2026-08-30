plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-contract"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils-read-write-mutex"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-contract-ext"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
