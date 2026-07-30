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

    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "120s"
                }
            }
        }
        binaries.library()
    }
}
