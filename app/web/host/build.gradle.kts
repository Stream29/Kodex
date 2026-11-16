plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-rpc-protocol"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(project(":utils-kauri"))
            implementation(project(":utils-external-url"))
            implementation(project(":utils-process-client"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
            implementation(project(":rpc-backend-control-client"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
        nativeTest.dependencies {
            implementation("io.ktor:ktor-network:${libs.versions.ktor.asProvider().get()}")
            implementation(project(":app-backend-host"))
            implementation(project(":app-rpc"))
            implementation(project(":rpc-client"))
            implementation(project(":rpc-local"))
            implementation(project(":openai-client-test"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
            implementation(project(":utils-shell-client-system"))
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    val sandboxDirProvider = layout.buildDirectory.dir("tmp/$name/kodex-roundtrip-sandbox-$name")
    val sandboxFile = sandboxDirProvider.get().asFile
    val osHomeFile = sandboxFile.resolve("os-home")
    val osConfigFile = sandboxFile.resolve("os-config")
    environment("KODEX_FRONTEND_ROUNDTRIP_SANDBOX", sandboxFile.absolutePath)
    environment("HOME", osHomeFile.absolutePath)
    environment("USERPROFILE", osHomeFile.absolutePath)
    environment("XDG_CONFIG_HOME", osConfigFile.absolutePath)
    environment("BASH_ENV", "")
    environment("ENV", "")
    if (File.separatorChar == '/') {
        environment("SHELL", "/bin/sh")
        environment("ZDOTDIR", osHomeFile.absolutePath)
    }
    doFirst {
        sandboxFile.deleteRecursively()
        osHomeFile.mkdirs()
        osConfigFile.mkdirs()
    }
}
