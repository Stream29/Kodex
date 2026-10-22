plugins {
    kotlin("multiplatform")
    `maven-publish`
    id("kodex.ktor-native-network-override")
}

configureCoordinates()

kotlin {
    configureCompiler()
    configureDesktopTargets()
}
