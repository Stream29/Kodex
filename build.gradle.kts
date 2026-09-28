// Replace only the affected kRPC 0.10.3 utils module. Keep the compiler, core,
// transport, and wire protocol at the upstream version.
subprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("org.jetbrains.kotlinx:kotlinx-rpc-utils"))
                .using(project(":rpc-krpc-utils-patch"))
                .because("kRPC 0.10.3 Native map accessors return live collections")
        }
    }
}
