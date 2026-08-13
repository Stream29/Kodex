package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataSource
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageStore
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam

internal class SettingsViewModelImpl(
    initialPage: SettingsPage,
    override val global: GlobalSettingsViewModelImpl,
    override val session: SessionSettingsViewModelImpl,
    override val newSession: NewSessionSettingsViewModelImpl,
) : SettingsViewModel {
    private val mutableSelectedPage = MutableStateFlow(initialPage)
    private var closed: Boolean = false

    override val selectedPage: StateFlow<SettingsPage> = mutableSelectedPage.asStateFlow()

    init {
        if (initialPage == SettingsPage.Global) global.onVisible()
    }

    override fun selectPage(page: SettingsPage) {
        if (closed || mutableSelectedPage.value == page) return
        if (page != SettingsPage.Global) global.dismissUsageReset()
        mutableSelectedPage.value = page
        if (page == SettingsPage.Global) global.onVisible()
    }

    override fun close() {
        if (closed) return
        closed = true
        global.close()
        session.close()
        newSession.close()
    }
}

/**
 * Creates one directly rendered, short-lived Settings ViewModel.
 *
 * The caller scope remains the command owner so writes accepted before popup
 * disposal can finish. [SettingsViewModel.close] releases only Settings-owned
 * collectors, workflows, and the supplied fixed-target adapter.
 */
public fun createSettingsViewModel(
    initialPage: SettingsPage,
    globalSettings: KodexGlobalSettingsStore,
    authentication: OpenAiAuthStore,
    accountUsage: CodexAccountUsageStore,
    mcpService: McpService,
    models: StateFlow<List<ModelInfo>>,
    sessionSettings: SessionSettingsDataSource? = null,
    createDirectoryPicker: (Path) -> DirectoryPickerViewModel? = { null },
    ownerScope: CoroutineScope,
): SettingsViewModel {
    val global = GlobalSettingsViewModelImpl(
        globalSettings = globalSettings,
        authentication = authentication,
        accountUsageStore = accountUsage,
        mcpService = mcpService,
        models = models,
        commandScope = ownerScope,
    )
    val session = SessionSettingsViewModelImpl(
        source = sessionSettings ?: UnavailableSessionSettingsDataSource(),
        models = models,
        parentScope = ownerScope,
        createDirectoryPicker = createDirectoryPicker,
    )
    val newSession = NewSessionSettingsViewModelImpl(
        globalSettings = globalSettings,
        models = models,
        parentScope = ownerScope,
    )
    return SettingsViewModelImpl(
        initialPage = initialPage,
        global = global,
        session = session,
        newSession = newSession,
    )
}

/** Exact process and popup dependencies for one Settings hierarchy. */
public data class SettingsViewModelDependencies(
    public val initialPage: SettingsPage,
    public val globalSettings: KodexGlobalSettingsStore,
    public val authentication: OpenAiAuthStore,
    public val accountUsage: CodexAccountUsageStore,
    public val mcpService: McpService,
    public val models: StateFlow<List<ModelInfo>>,
    public val sessionSettings: SessionSettingsDataSource?,
    public val createDirectoryPicker: (Path) -> DirectoryPickerViewModel?,
    public val ownerScope: CoroutineScope,
)

/** Koin-resolved creator for one exact Settings popup hierarchy. */
@Factory
public class DefaultSettingsViewModelFactory(
    @InjectedParam private val dependencies: SettingsViewModelDependencies,
) {
    public fun create(): SettingsViewModel =
        createSettingsViewModel(
            initialPage = dependencies.initialPage,
            globalSettings = dependencies.globalSettings,
            authentication = dependencies.authentication,
            accountUsage = dependencies.accountUsage,
            mcpService = dependencies.mcpService,
            models = dependencies.models,
            sessionSettings = dependencies.sessionSettings,
            createDirectoryPicker = dependencies.createDirectoryPicker,
            ownerScope = dependencies.ownerScope,
        )
}
