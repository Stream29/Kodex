plugins {
    kotlin("multiplatform")
    `maven-publish`
    id("de.infix.testBalloon")
}

configureCoordinates()

kotlin {
    configureCompiler()
    configureHostTargets()
    configureCommonTests(project)
}
