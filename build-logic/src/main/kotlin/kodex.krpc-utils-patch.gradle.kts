// Preserve the upstream utility replacement for actual kRPC consumers.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains.kotlinx:kotlinx-rpc-utils"))
            .using(project(":rpc-impl-krpc-utils-patch"))
            .because("kRPC 0.10.3 Native map accessors return live collections")
    }
}
