import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    // Preserve the former Host/CLI implementation target options after consolidation.
    mingwX64 {
        binaries.all { linkerOpts("-lole32") }
    }
    // Lucene belongs to concrete platform implementations, not shared API metadata.
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").defaultSourceSet.apply {
            kotlin.srcDir("src/luceneMain/kotlin")
            dependencies {
                implementation(libs.lucene.kmp.core)
            }
        }
    }
}
