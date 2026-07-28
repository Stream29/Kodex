plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
            implementation(project(":agent-storage-filesystem"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-filesystem-lease"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-read-write-mutex"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.cache4k)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
