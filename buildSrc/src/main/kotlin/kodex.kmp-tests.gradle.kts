plugins {
    kotlin("multiplatform")
    id("de.infix.testBalloon")
}

kotlin {
    configureCommonTests(project)
}
