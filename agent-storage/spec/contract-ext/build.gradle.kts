plugins {
    id("kodex.kmp-host")
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
