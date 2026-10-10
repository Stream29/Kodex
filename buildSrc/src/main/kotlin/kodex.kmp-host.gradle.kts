plugins {
    kotlin("multiplatform")
    `maven-publish`
}

configureCoordinates()

kotlin {
    configureCompiler()
    configureHostTargets()

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
