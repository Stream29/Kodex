package io.github.stream29.kodex.cli.app

/** Test support only: production entrypoints never depend on this module. */
public enum class ApplicationStartupPhase { HomePrepared, BackendReady, ServicesRegistered, FrontendReady, RendererStarted }
