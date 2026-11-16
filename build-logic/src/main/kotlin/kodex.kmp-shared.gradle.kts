@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
}

configureCoordinates()
configureWasmNodeCommand()

kotlin {
    configureCompiler()
    configureFrontendTargets()
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                    useConfigDirectory(layout.settingsDirectory.dir("gradle/wasm-karma.config.d").asFile)
                }
            }
        }
    }
}
