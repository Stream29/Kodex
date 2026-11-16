plugins {
    kotlin("multiplatform")
}

configureCoordinates()

kotlin {
    configureCompiler()
    configureDesktopTargets()
}
