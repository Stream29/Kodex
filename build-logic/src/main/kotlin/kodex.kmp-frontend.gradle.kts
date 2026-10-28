plugins {
    kotlin("multiplatform")
    `maven-publish`
    id("kodex.ktor-native-network-override")
}

configureCoordinates()
configureComposeWasmUi()
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
