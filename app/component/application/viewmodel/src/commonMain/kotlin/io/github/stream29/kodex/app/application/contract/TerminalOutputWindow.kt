package io.github.stream29.kodex.app.application.contract

private const val MaxUnits = 524_288
private const val MaxItems = 4_096
private const val MaxFragment = 4_096
private const val BlockSize = 64
private const val MarkerUnits = 64

/** Sealed blocks are shared by immutable snapshots; only the current block and open line are copied. */
internal class TerminalOutputWindow {
    private val blocks = ArrayDeque<List<TerminalOutputItem>>()
    private val staging = ArrayList<TerminalOutputItem>(BlockSize)
    private var frontTrim = 0
    private var sealedUnits = 0
    private var evicted = 0L
    private var nextId = 1L
    private var openId = 0L
    private val line = StringBuilder()
    private var dirty = false

    fun append(cp: Int) {
        dirty = true
        if (cp == 0x0a) {
            sealLine(endsLine = true)
            return
        }
        val units = if (cp <= 0xffff) 1 else 2
        if (line.length + units > MaxFragment) sealLine(endsLine = false)
        if (openId == 0L) openId = nextId++
        if (units == 1) line.append(cp.toChar()) else {
            val adjusted = cp - 0x10000
            line.append((0xd800 + (adjusted shr 10)).toChar())
            line.append((0xdc00 + (adjusted and 0x3ff)).toChar())
        }
    }

    fun omit(bytes: ULong) {
        if (line.isNotEmpty()) sealLine(endsLine = false)
        add(TerminalOutputItem.BackendOmission(nextId++, bytes))
    }

    private fun sealLine(endsLine: Boolean) {
        add(TerminalOutputItem.Text(if (openId == 0L) nextId++ else openId, line.toString(), endsLine))
        openId = 0L
        line.setLength(0)
    }

    private fun add(item: TerminalOutputItem) {
        dirty = true
        sealedUnits += item.units()
        staging.add(item)
        if (staging.size == BlockSize) {
            blocks.addLast(staging.toList())
            staging.clear()
        }
        // Bound staging as well, including a dense slice containing only line breaks.
        trim(openUnits = 0, openItems = 0)
    }

    private fun trim(openUnits: Int, openItems: Int) {
        while (true) {
            val marker = if (evicted == 0L) 0 else 1
            val sealedItems = blocks.size * BlockSize - frontTrim + staging.size
            if (sealedItems + openItems + marker <= MaxItems &&
                sealedUnits + openUnits + marker * MarkerUnits <= MaxUnits
            ) return
            val removed = if (blocks.isEmpty()) staging.removeAt(0) else {
                blocks.first()[frontTrim++].also {
                    if (frontTrim == BlockSize) {
                        frontTrim = 0
                        blocks.removeFirst()
                    }
                }
            }
            sealedUnits -= removed.units()
            evicted++
        }
    }

    fun takeSnapshot(): List<TerminalOutputItem>? {
        if (!dirty) return null
        dirty = false
        val open = if (openId == 0L) null else TerminalOutputItem.Text(openId, line.toString(), false)
        trim(open?.units() ?: 0, if (open == null) 0 else 1)
        return TerminalItemsSnapshot(
            blocks.toList(), frontTrim, staging.toList(), open,
            if (evicted == 0L) null else TerminalOutputItem.WindowEviction(evicted),
        )
    }
}

private fun TerminalOutputItem.units(): Int = when (this) {
    is TerminalOutputItem.Text -> text.length + if (endsLine) 1 else 0
    else -> MarkerUnits
}

private class TerminalItemsSnapshot(
    private val blocks: List<List<TerminalOutputItem>>,
    private val frontTrim: Int,
    private val staging: List<TerminalOutputItem>,
    private val open: TerminalOutputItem.Text?,
    private val eviction: TerminalOutputItem.WindowEviction?,
) : AbstractList<TerminalOutputItem>() {
    private val prefix = if (eviction == null) 0 else 1
    private val inBlocks = blocks.size * BlockSize - frontTrim
    override val size = prefix + inBlocks + staging.size + if (open == null) 0 else 1

    override fun get(index: Int): TerminalOutputItem {
        if (index !in indices) throw IndexOutOfBoundsException()
        val item = index - prefix
        if (item < 0) return checkNotNull(eviction)
        if (item < inBlocks) {
            val absolute = item + frontTrim
            return blocks[absolute / BlockSize][absolute % BlockSize]
        }
        val last = item - inBlocks
        return if (last < staging.size) staging[last] else checkNotNull(open)
    }
}
