plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-client")
    id("kodex.kmp-cli-host-layer")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("cliHostMain").dependencies {
            implementation(project(":utils-process-client-system"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
