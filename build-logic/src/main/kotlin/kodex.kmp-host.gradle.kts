plugins {
    kotlin("multiplatform")
    `maven-publish`
}

configureCoordinates()

kotlin {
    configureCompiler()
    configureDesktopTargets()
}
