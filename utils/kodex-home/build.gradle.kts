plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.io.core)
            implementation(project(":utils-os-environment"))
        }
    }
}
