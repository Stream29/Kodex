import org.jetbrains.kotlin.gradle.idea.proto.com.google.protobuf.api

plugins {
    id("codexlite.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":utils"))
        }
    }
}
