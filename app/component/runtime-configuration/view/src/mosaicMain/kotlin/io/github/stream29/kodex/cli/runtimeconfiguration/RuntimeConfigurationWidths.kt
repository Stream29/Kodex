package io.github.stream29.kodex.cli.runtimeconfiguration

import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfiguration
import io.github.stream29.kodex.cli.components.buttonWidth

public actual fun runtimeConfigurationButtonWidths(
    configuration: RuntimeConfiguration,
): List<Int> = listOf(
    buttonWidth(
        runtimeConfigurationLabel(
            model = configuration.model,
            reasoning = configuration.reasoning,
            tier = configuration.tier,
        ),
    ),
    buttonWidth(runtimeRequestUserInputModeLabel(configuration.requestUserInputMode)),
)
