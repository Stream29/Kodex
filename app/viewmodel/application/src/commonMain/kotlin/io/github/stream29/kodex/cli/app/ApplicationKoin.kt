package io.github.stream29.kodex.cli.app

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module

/**
 * Shared application bootstrap graph.
 *
 * Infrastructure instances whose values depend on process paths are supplied
 * by [KodexApplication]. Component scanning contributes the typed application
 * factory that consumes those exact runtime parameters.
 */
@Module
@ComponentScan("io.github.stream29.kodex.cli.app")
internal class KodexApplicationModule

/** Marker consumed by Koin's compiler-generated isolated startup API. */
@KoinApplication(modules = [KodexApplicationModule::class])
internal class KodexKoinApplication
