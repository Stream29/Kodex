package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.cli.rpc.RpcGlobalSettings
import io.github.stream29.kodex.cli.settings.MinimumSidebarWidthColumns
import io.github.stream29.kodex.cli.settings.SidebarSettings
import io.github.stream29.kodex.cli.settings.ToolWindowId
import io.github.stream29.kodex.cli.settings.ToolWindowSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

internal fun createSidebarSettingsViewModel(
    global: RpcGlobalSettings,
    scope: CoroutineScope,
    initialized: Boolean,
): SidebarSettingsViewModel = SidebarSettingsViewModelImpl(global, scope, initialized)

/** Contents persist locally; widths belong to this frontend invocation only. */
internal class SidebarSettingsViewModelImpl(
    private val global: RpcGlobalSettings,
    scope: CoroutineScope,
    private var initialized: Boolean,
) : SidebarSettingsViewModel {
    private fun project(): SidebarSettings {
        val values = global.frontend.settings.value.sidebars
        val widths = global.sidebarWidths.value
        return SidebarSettings(values.activeLeft, values.activeRight,
            widths.first.coerceAtLeast(MinimumSidebarWidthColumns),
            widths.second.coerceAtLeast(MinimumSidebarWidthColumns))
    }
    override val state = combine(global.frontend.settings, global.sidebarWidths) { _, _ -> project() }
        .stateIn(scope, SharingStarted.Eagerly, project())
    override fun initializeViewport(columns: Int) {
        if (initialized) return
        require(columns >= 0)
        initialized = true
        global.resizeSidebars(columns / 4, columns / 4)
    }
    override suspend fun toggle(id: ToolWindowId) {
        global.frontend.update { current ->
            val sidebars = current.sidebars
            current.copy(sidebars = when (id.side) {
                ToolWindowSide.Left -> sidebars.copy(activeLeft = id.toggle(sidebars.activeLeft))
                ToolWindowSide.Right -> sidebars.copy(activeRight = id.toggle(sidebars.activeRight))
            })
        }
    }
    override suspend fun collapse(side: ToolWindowSide) {
        global.frontend.update { current ->
            current.copy(sidebars = when (side) {
                ToolWindowSide.Left -> current.sidebars.copy(activeLeft = null)
                ToolWindowSide.Right -> current.sidebars.copy(activeRight = null)
            })
        }
    }
    override suspend fun resizeLeft(columns: Int) { global.resizeSidebars(columns, global.sidebarWidths.value.second) }
    override suspend fun resizeRight(columns: Int) { global.resizeSidebars(global.sidebarWidths.value.first, columns) }
}
