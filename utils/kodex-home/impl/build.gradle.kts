plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-kodex-home-spec"))
            api(libs.kotlinx.io.core)
            implementation(project(":utils-os-environment-impl"))
        }
    }
}
