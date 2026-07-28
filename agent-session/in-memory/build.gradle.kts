plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-session-composition"))
            api(project(":agent-session-contract"))
            implementation(project(":agent-storage-in-memory"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-session-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
