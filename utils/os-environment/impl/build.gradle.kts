plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-os-environment-spec"))
            api(libs.kotlinx.io.core)
        }
    }
}
