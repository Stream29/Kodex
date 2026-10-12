plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-core"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-core"))
        }
    }
}
