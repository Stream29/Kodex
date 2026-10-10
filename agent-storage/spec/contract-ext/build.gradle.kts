plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-spec-contract"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-impl-in-memory"))
        }
    }
}
