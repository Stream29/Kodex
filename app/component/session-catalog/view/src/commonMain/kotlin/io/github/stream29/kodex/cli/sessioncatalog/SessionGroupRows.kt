package io.github.stream29.kodex.cli.sessioncatalog

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupFocus
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupsState

internal fun SessionGroupsState.rows(focus: SessionGroupFocus?): List<SessionGroupRow> {
    val groups = groups ?: return listOf(SessionGroupRow.Loading)
    if (groups.isEmpty()) return listOf(SessionGroupRow.Empty)
    val labels = groupLabels(groups.map { it.cwd })
    return buildList {
        groups.forEach { group ->
            val cwd = group.cwd
            val (title, subtitle) = labels.getValue(cwd)
            val expanded = isExpanded(cwd, focus)
            val showArchived = cwd in showArchivedGroups
            add(SessionGroupRow.Header(cwd, title, subtitle, expanded, showArchived, group.hasRunning))
            if (!expanded) return@forEach
            val page = pages[cwd]
            if (page == null) {
                add(SessionGroupRow.LoadingEntries(cwd))
                return@forEach
            }
            // Keep successful rows until the new request completes, but never
            // expose archived entries after the user disables their visibility.
            val visible = if (showArchived) page.entries else page.entries.filterNot { it.archived }
            val all = cwd in expandedLimitGroups
            val entries = if (all) visible else visible.take(5)
            entries.forEach { entry -> add(SessionGroupRow.Entry(cwd, entry, focus?.agentId == entry.rootAgentId)) }
            val hidden = page.hiddenCount + (visible.size - entries.size)
            if (all) add(SessionGroupRow.Limit(cwd, null))
            else if (hidden > 0) add(SessionGroupRow.Limit(cwd, hidden))
        }
    }
}

internal data class SessionDirectoryLabel(val title: String, val subtitle: String?)

/** Exact keys remain unchanged; paths are parsed only to choose display labels. */
internal fun groupLabels(cwds: List<String?>): Map<String?, SessionDirectoryLabel> {
    val pathsByTitle = cwds.groupBy(::directoryTitle)
    return buildMap {
        pathsByTitle.forEach { (title, paths) ->
            if (paths.size == 1) {
                put(paths.single(), SessionDirectoryLabel(title, null))
                return@forEach
            }
            val candidates = paths.associateWith { it?.let(::parentSuffixes).orEmpty() }
            val frequencies = mutableMapOf<String, Int>()
            candidates.values.forEach { suffixes ->
                suffixes.forEach { suffix -> frequencies[suffix] = frequencies.getOrElse(suffix) { 0 } + 1 }
            }
            paths.forEach { path ->
                val subtitle = candidates.getValue(path).firstOrNull { frequencies[it] == 1 } ?: path
                put(path, SessionDirectoryLabel(title, subtitle))
            }
        }
    }
}

private fun directoryTitle(path: String?): String {
    if (path == null) return "未设置目录"
    val trimmed = path.trimEnd('/', '\\')
    if (trimmed.isEmpty() || trimmed.length == 2 && trimmed[0].isLetter() && trimmed[1] == ':' && trimmed != path) {
        return path
    }
    return trimmed.substring(maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\')) + 1)
}

private val PathSegment = Regex("[^/\\\\]+")

private fun parentSuffixes(path: String): Set<String> {
    val trimmed = path.trimEnd('/', '\\')
    val basename = PathSegment.findAll(trimmed).lastOrNull() ?: return emptySet()
    val parentWithSeparator = trimmed.substring(0, basename.range.first)
    val parent = parentWithSeparator.trimEnd('/', '\\')
    return buildSet {
        PathSegment.findAll(parent).toList().asReversed().forEach { add(parent.substring(it.range.first)) }
        if (parentWithSeparator.isNotEmpty()) {
            // Keep the root distinguishable from a relative path. A drive-only
            // parent retains the original separator as part of its root.
            val driveRoot = parent.length == 2 && parent[0].isLetter() && parent[1] == ':'
            add(if (parent.isEmpty() || driveRoot) parentWithSeparator else parent)
        }
    }
}
