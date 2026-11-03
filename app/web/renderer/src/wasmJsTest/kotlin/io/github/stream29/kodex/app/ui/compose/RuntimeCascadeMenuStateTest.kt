package io.github.stream29.kodex.app.ui.compose

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeMenuEntry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

private fun leaf(key: String, onSelect: () -> Unit) =
    RuntimeMenuEntry.Command(key, key, false, onSelect)

private fun branch(key: String, children: () -> List<RuntimeMenuEntry>) =
    RuntimeMenuEntry.Branch(key, key, false, "", children)

val runtimeCascadeMenuStateTest by testSuite {
    test("failed command remains closed and repeated target cannot submit again") {
        var attempts = 0
        val state = RuntimeCascadeMenuState()
        state.update(Any(), listOf(leaf("failure") { attempts++; error("rejected") }), true)
        state.open()
        val target = state.target(0, "failure")!!
        assertFailsWith<IllegalStateException> { state.activate(target) }
        state.activate(target)
        assertFalse(state.isOpen)
        assertEquals(1, attempts)
    }

    test("navigation keeps ancestors and only a leaf writes") {
        var writes = 0
        val state = RuntimeCascadeMenuState()
        state.update(Any(), listOf(branch("model") {
            listOf(branch("effort") { listOf(leaf("tier") { writes++ }) })
        }), true)
        state.open()
        state.activate(state.target(0, "model")!!)
        state.activate(state.target(1, "effort")!!)
        assertEquals(listOf("model", "effort"), state.path)
        assertEquals(listOf("model"), state.entries(0).map { it.key })
        assertEquals(listOf("effort"), state.entries(1).map { it.key })
        assertEquals(0, writes)
        val target = state.target(2, "tier")!!
        state.activate(target)
        state.activate(target)
        assertEquals(1, writes)
        assertFalse(state.isOpen)
    }

    test("catalog refresh uses latest command and truncates invalid paths") {
        var old = 0
        var latest = 0
        val owner = Any()
        val state = RuntimeCascadeMenuState()
        state.update(owner, listOf(branch("m") { listOf(leaf("t") { old++ }) }), true)
        state.open()
        state.activate(state.target(0, "m")!!)
        val target = state.target(1, "t")!!
        state.update(owner, listOf(branch("m") { listOf(leaf("t") { latest++ }) }), true)
        state.activate(target)
        assertEquals(0, old)
        assertEquals(1, latest)
        state.open()
        state.activate(state.target(0, "m")!!)
        val stale = state.target(1, "t")!!
        state.update(owner, listOf(branch("other") { emptyList() }), true)
        assertTrue(state.path.isEmpty())
        state.activate(stale)
        assertEquals(1, latest)
    }

    test("switching branches, owner or open generation rejects stale events") {
        var writes = 0
        val owner = Any()
        val roots = listOf(
            branch("a") { listOf(leaf("x") { writes++ }) },
            branch("b") { listOf(leaf("x") { writes++ }) },
        )
        val state = RuntimeCascadeMenuState()
        state.update(owner, roots, true)
        state.open()
        state.activate(state.target(0, "a")!!)
        val stale = state.target(1, "x")!!
        state.activate(state.target(0, "b")!!)
        state.activate(stale)
        assertEquals(listOf("b"), state.path)
        state.update(Any(), roots, true)
        assertFalse(state.isOpen)
        state.open()
        state.activate(stale)
        assertEquals(0, writes)
        state.update(owner, roots, false)
        assertFalse(state.isOpen)
    }

    test("back closes one level at a time without writing") {
        val state = RuntimeCascadeMenuState()
        state.update(Any(), listOf(branch("m") { listOf(branch("e") { emptyList() }) }), true)
        state.open()
        state.activate(state.target(0, "m")!!)
        state.activate(state.target(1, "e")!!)
        assertTrue(state.back())
        assertEquals(listOf("m"), state.path)
        assertTrue(state.back())
        assertTrue(state.path.isEmpty())
        assertTrue(state.back())
        assertFalse(state.isOpen)
        assertFalse(state.back())
    }

    test("switching a parent submits only the current three-level tuple") {
        val writes = mutableListOf<Triple<String, String, String>>()
        fun model(name: String) = branch(name) {
            listOf(branch("high") {
                listOf(leaf("fast") { writes += Triple(name, "high", "fast") })
            })
        }
        val state = RuntimeCascadeMenuState()
        state.update(Any(), listOf(model("first"), model("second")), true)
        state.open()
        state.activate(state.target(0, "first")!!)
        state.activate(state.target(1, "high")!!)
        val oldLeaf = state.target(2, "fast")!!
        state.activate(state.target(0, "second")!!)
        state.activate(oldLeaf)
        assertTrue(writes.isEmpty())
        state.activate(state.target(1, "high")!!)
        state.activate(state.target(2, "fast")!!)
        assertEquals(listOf(Triple("second", "high", "fast")), writes)
    }
}
