plugins {
    id("codexlite.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":utils-process-client"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
