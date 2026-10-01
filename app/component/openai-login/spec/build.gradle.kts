plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-shared-auth-contract"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
