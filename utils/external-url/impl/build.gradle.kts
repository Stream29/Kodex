plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-external-url-spec"))
            implementation(project(":utils-process-client-impl"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
