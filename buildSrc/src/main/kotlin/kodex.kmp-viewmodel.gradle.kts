import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kodex.kmp-cli")
    id("io.insert-koin.compiler.plugin")
}

val libs = extensions
    .getByType<VersionCatalogsExtension>()
    .named("libs")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("koin-core").get())
            implementation(libs.findLibrary("koin-annotations").get())
        }
    }
}

koinCompiler {
    compileSafety = true
    strictSafety = true
    unsafeDslChecks = true
}
