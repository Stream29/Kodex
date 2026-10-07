plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-application-preferences-spec"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
