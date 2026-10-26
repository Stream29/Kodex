package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.application.contract.ApplicationViewModel
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelFactory
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogDependencies
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModelFactory
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModelFactory
import io.github.stream29.kodex.app.settings.contract.SettingsViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

/**
 * Creates the navigation/popup owner using the same typed ports as the CLI host.
 * The caller owns [ownerScope] and must close and shut down the returned owner.
 * Its CoroutineExceptionHandler is the existing unhandled-error outlet for an
 * accepted submission whose renderer waiter has gone away.
 * No renderer, RPC transport or backend lifetime is created here.
 */
public expect fun createApplicationViewModel(
    sessions: PersistedSessionViewModelRegistry,
    newSessionFactory: NewSessionViewModelFactory,
    catalogFactory: SessionCatalogViewModelFactory,
    catalogDependencies: SessionCatalogDependencies,
    settingsFactory: SettingsViewModelFactory,
    loginFactory: OpenAiLoginViewModelFactory,
    createDirectoryPicker: (Path) -> DirectoryPickerViewModel,
    newSessionArguments: NewSessionViewModelArguments,
    ownerScope: CoroutineScope,
): ApplicationViewModel
