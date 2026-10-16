plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-shared")
    id("kodex.kmp-cli-host-layer")
    id("kodex.ktor-native-network-override")
    id("kodex.kmp-tests")
}

kotlin {
    // Preserve the former Host/CLI implementation target options after consolidation.
    mingwX64 {
        binaries.all { linkerOpts("-lole32") }
    }
    js {
        nodejs {
            testTask {
                useMocha { timeout = "120s" }
            }
        }
    }
}

kotlin {
    sourceSets {
        named("cliHostMain").dependencies {
            implementation(project(":utils-process-client-system"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
