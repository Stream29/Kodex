plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-contract"))
            api(project(":utils-kotlinx-io-coroutines"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-runtime-impl"))
            implementation(project(":agent-state-impl"))
            implementation(project(":agent-storage-filesystem"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-filesystem-lease-impl"))
            implementation(project(":utils-read-write-mutex"))
            implementation(libs.cache4k)
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-contract-ext"))
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
