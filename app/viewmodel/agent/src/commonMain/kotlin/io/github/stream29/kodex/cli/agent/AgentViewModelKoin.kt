package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.ComposerViewModelFactory
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

@Module
@Configuration
@ComponentScan("io.github.stream29.kodex.cli.agent")
public class AgentViewModelModule

@Factory(binds = [ComposerViewModelFactory::class])
internal fun createComposerViewModelFactory(): ComposerViewModelFactory =
    DefaultComposerViewModelFactory
