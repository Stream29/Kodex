package io.github.stream29.kodex.cli.runtimeconfiguration

import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfiguration

/** Actual trigger measurements shared by Agent and draft statusbar row planning. */
public expect fun runtimeConfigurationButtonWidths(
    configuration: RuntimeConfiguration,
): List<Int>
