import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

configureCoordinates()
configureComposeWasmUi()
configureWasmNodeCommand()
val kotlinVersion = extensions.getByType<VersionCatalogsExtension>()
    .named("libs").findVersion("kotlin").get().requiredVersion
kotlin {
    configureCompiler()
    configureBrowserTarget()
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                    useConfigDirectory(rootProject.file("gradle/wasm-karma.config.d"))
                }
            }
        }
    }
    sourceSets.named("specMain").dependencies {
        api("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    }
}
