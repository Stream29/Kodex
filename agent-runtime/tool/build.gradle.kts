plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-runtime-contract"))
            api(project(":tool-contract"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
