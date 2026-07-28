plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-shell-client"))
            api(libs.kotlinx.io.core)
        }
    }
}
