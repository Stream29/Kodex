package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.layout.Remeasurement
import com.jakewharton.mosaic.layout.RemeasurementModifier
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.node.ModifierNodeElement

internal data class LazyColumnRemeasurementElement(
    private val state: LazyListState,
) : ModifierNodeElement<LazyColumnRemeasurementNode>(),
    RemeasurementModifier {
    private var node: LazyColumnRemeasurementNode? = null
    private var remeasurement: Remeasurement? = null

    override fun create(): LazyColumnRemeasurementNode =
        LazyColumnRemeasurementNode(state).also { node ->
            this.node = node
            remeasurement?.let(node::updateRemeasurement)
        }

    override fun update(node: LazyColumnRemeasurementNode) {
        this.node = node
        node.updateState(state)
        remeasurement?.let(node::updateRemeasurement)
    }

    override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
        this.remeasurement = remeasurement
        node?.updateRemeasurement(remeasurement)
    }
}

internal class LazyColumnRemeasurementNode(
    private var state: LazyListState,
) : Modifier.Node() {
    private var mosaicRemeasurement: Remeasurement? = null
    private var stateRemeasurement: LazyListRemeasurement? = null

    override fun onAttach() {
        attachToState()
    }

    override fun onDetach() {
        detachFromState()
    }

    fun updateState(state: LazyListState) {
        if (this.state === state) return
        detachFromState()
        this.state = state
        attachToState()
    }

    fun updateRemeasurement(remeasurement: Remeasurement) {
        if (mosaicRemeasurement === remeasurement) return
        detachFromState()
        mosaicRemeasurement = remeasurement
        attachToState()
    }

    private fun attachToState() {
        if (!isAttached) return
        val remeasurement = mosaicRemeasurement ?: return
        val adapter = LazyListRemeasurement(remeasurement::forceRemeasure)
        stateRemeasurement = adapter
        state.attachRemeasurement(adapter)
    }

    private fun detachFromState() {
        stateRemeasurement?.let(state::detachRemeasurement)
        stateRemeasurement = null
    }
}
