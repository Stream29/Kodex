@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.targets.js.webpack.WebpackDevtool

plugins {
    id("kodex.kmp-web")
    kotlin("plugin.compose")
}

kotlin {
    wasmJs {
        binaries.executable()
        browser {
            // eval would parse the generated import.meta expression outside its ES module.
            commonWebpackConfig { devtool = WebpackDevtool.SOURCE_MAP }
        }
    }
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
    providers.gradleProperty("kodex.binaryenCommand").orNull?.let { executable ->
        extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec> {
            download.set(false)
            command.set(executable)
        }
    }
}
