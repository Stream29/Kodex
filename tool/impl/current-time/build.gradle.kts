plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-spec-current-time"))
            api(project(":tool-spec-contract"))
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.schema.json)
            implementation(project(":tool-impl-builder"))
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
