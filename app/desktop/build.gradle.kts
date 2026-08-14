plugins {
    id("kodex.jvm-desktop-application")
}

dependencies {
    implementation(project(":app-view-application"))
    implementation(project(":app-viewmodel-application"))
    implementation(project(":utils-kodex-home"))
    implementation(project(":utils-logging"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":app-view-components"))
    testImplementation(libs.compose.ui.test.junit4)
}
