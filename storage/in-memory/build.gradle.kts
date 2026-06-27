plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":storage:contract"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":utils:read-write-mutex"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
