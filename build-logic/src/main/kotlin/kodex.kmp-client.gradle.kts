plugins {
    kotlin("multiplatform")
    id("kodex.ktor-native-network-override")
}

configureCoordinates()

kotlin {
    configureCompiler()
    configureDesktopTargets()
}
