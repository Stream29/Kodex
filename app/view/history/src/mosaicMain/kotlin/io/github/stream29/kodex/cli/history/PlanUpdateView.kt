package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus

/** Renders an `update_plan` call as a user-facing checklist rather than a generic tool event. */
@Composable
internal fun StablePlanUpdate.renderPlanUpdate() {
    PlanUpdateView(
        title = "Update Plan",
        explanation = arguments.explanation,
        plan = arguments.plan,
        titleStyle = TextStyle.Bold,
    )
}

/** Keeps the in-flight update visible without exposing its tool-call details. */
@Composable
internal fun PendingPlanUpdate.renderPlanUpdate() {
    PlanUpdateView(
        title = "Updating Plan",
        explanation = arguments.explanation,
        plan = arguments.plan,
        titleStyle = TextStyle.Dim,
        detailStyle = TextStyle.Dim,
    )
}

@Composable
private fun PlanUpdateView(
    title: String,
    explanation: String?,
    plan: List<PlanItemArg>,
    titleStyle: TextStyle,
    detailStyle: TextStyle = TextStyle.Unspecified,
) {
    val note = explanation?.trim()?.takeIf(String::isNotEmpty)
    Column(modifier = Modifier.fillMaxWidth()) {
        HistoryItemHeader(
            value = "• $title",
            modifier = Modifier.fillMaxWidth(),
            textStyle = titleStyle,
        )
        note?.let { WrappedHistoryText("  └ $it", TextStyle.Dim) }
        if (plan.isEmpty()) {
            val indent = if (note == null) "  └ " else "    "
            WrappedHistoryText("${indent}(no steps provided)", TextStyle.Dim)
        } else {
            plan.forEachIndexed { index, item ->
                val indent = if (note == null && index == 0) "  └ " else "    "
                WrappedHistoryText("$indent${item.status.marker()} ${item.step}", detailStyle)
            }
        }
    }
}

private fun StepStatus.marker(): String = when (this) {
    StepStatus.Pending -> "[ ]"
    StepStatus.InProgress -> "[>]"
    StepStatus.Completed -> "[x]"
}
