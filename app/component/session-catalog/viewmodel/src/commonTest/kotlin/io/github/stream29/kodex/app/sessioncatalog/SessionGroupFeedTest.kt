@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.app.sessioncatalog

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupFocus
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupPage
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupReads
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

val sessionGroupFeedTest by testSuite {
    test("mount reads only current and default expanded groups, and local choices survive unmount") {
        runTest {
            val reads = GroupReads()
            val feed = SessionGroupFeed(reads)
            val focused = MutableStateFlow<SessionGroupFocus?>(SessionGroupFocus("/old", 42))
            assertEquals(0, reads.summaryCalls)
            val mount = launch { feed.observe(focused) { throw it } }
            runCurrent()
            assertEquals(1, reads.summaryCalls)
            assertEquals(listOf("/recent", "/previous", "/old", null), feed.state.value.groups!!.map { it.cwd })
            assertEquals(setOf<String?>("/recent", "/previous", "/old"), reads.activeGroups)
            assertTrue(reads.pageCalls.all { !it.archived && it.limit == 5 })

            feed.toggleArchived("/old")
            feed.toggleLimit("/old")
            feed.toggleGroup("/recent", focused.value)
            runCurrent()
            assertEquals(setOf<String?>("/previous", "/old"), reads.activeGroups)
            assertEquals(GroupCall("/old", true, null), reads.pageCalls.last())
            assertEquals(1, reads.groupStarts.getValue("/old"))
            val count = reads.pageCalls.size
            reads.emit("/recent")
            runCurrent()
            assertEquals(count, reads.pageCalls.size)

            mount.cancelAndJoin()
            assertTrue(reads.activeGroups.isEmpty())
            assertEquals(0, reads.activeSummary)
            assertNull(feed.state.value.groups)
            assertTrue(feed.state.value.pages.isEmpty())
            val remount = launch { feed.observe(focused) { throw it } }
            runCurrent()
            assertEquals(setOf<String?>("/previous", "/old"), reads.activeGroups)
            assertEquals(GroupCall("/old", true, null), reads.pageCalls.last { it.cwd == "/old" })
            remount.cancelAndJoin()
        }
    }

    test("group invalidation refreshes only its page, retaining a snapshot during read and conflating bursts") {
        runTest {
            val reads = GroupReads()
            val feed = SessionGroupFeed(reads)
            val mount = launch { feed.observe(MutableStateFlow(null)) { throw it } }
            runCurrent()
            val oldPage = feed.state.value.pages.getValue("/recent")
            val blocked = CompletableDeferred<Unit>()
            reads.gates["/recent"] = blocked
            reads.emit("/recent")
            runCurrent()
            assertEquals(oldPage, feed.state.value.pages["/recent"])
            repeat(4) { reads.emit("/recent"); runCurrent() }
            blocked.complete(Unit)
            runCurrent()
            assertEquals(3, reads.pageCalls.count { it.cwd == "/recent" })
            assertEquals(1, reads.pageCalls.count { it.cwd == "/previous" })
            assertEquals(1, reads.summaryCalls)
            assertEquals(5L, feed.state.value.pages.getValue("/recent").groupNonce)

            reads.summaryNonce.value++
            runCurrent()
            assertEquals(2, reads.summaryCalls)
            assertEquals(4, reads.pageCalls.size)
            feed.refresh()
            runCurrent()
            assertEquals(3, reads.summaryCalls)
            assertEquals(6, reads.pageCalls.size)
            mount.cancelAndJoin()
        }
    }

    test("no current Agent and an Agent tracking the unset cwd have different subscriptions") {
        runTest {
            val reads = GroupReads()
            val feed = SessionGroupFeed(reads)
            val focus = MutableStateFlow<SessionGroupFocus?>(null)
            val mount = launch { feed.observe(focus) { throw it } }
            runCurrent()
            assertTrue(null !in reads.activeGroups)
            focus.value = SessionGroupFocus(null, 4)
            runCurrent()
            assertTrue(null in reads.activeGroups)
            focus.value = null
            runCurrent()
            assertTrue(null !in reads.activeGroups)
            mount.cancelAndJoin()
        }
    }
}

private data class GroupCall(val cwd: String?, val archived: Boolean, val limit: Int?)

private class GroupReads : SessionGroupReads {
    private val groups = listOf(null, "/old", "/previous", "/recent")
    private val nonces = groups.associateWith { MutableStateFlow(0L) }
    val summaryNonce = MutableStateFlow(0L)
    var summaryCalls = 0
    var activeSummary = 0
    val groupStarts = mutableMapOf<String?, Int>()
    val activeGroups = mutableSetOf<String?>()
    val pageCalls = mutableListOf<GroupCall>()
    val gates = mutableMapOf<String?, CompletableDeferred<Unit>>()

    fun emit(cwd: String?) { nonces.getValue(cwd).value++ }

    override fun getSessionGroupsNonceFlow(): Flow<Long> = flow {
        activeSummary++
        try { emitAll(summaryNonce) } finally { activeSummary-- }
    }

    override suspend fun listSessionGroups(): List<SessionGroupSummary> = groups.mapIndexed { index, cwd ->
        SessionGroupSummary(cwd, nonces.getValue(cwd).value, Instant.fromEpochSeconds(if (cwd == null) 9 else index.toLong()), false)
    }.also { summaryCalls++ }

    override fun getSessionGroupNonceFlow(cwd: String?): Flow<Long> = flow {
        groupStarts[cwd] = groupStarts.getOrElse(cwd) { 0 } + 1
        activeGroups += cwd
        try { emitAll(nonces.getValue(cwd)) } finally { activeGroups -= cwd }
    }

    override suspend fun listSessionsInGroup(cwd: String?, includeArchived: Boolean, limit: Int?): SessionGroupPage {
        pageCalls += GroupCall(cwd, includeArchived, limit)
        gates[cwd]?.await()
        val nonce = nonces.getValue(cwd).value
        return SessionGroupPage(cwd, nonce, listOf(SessionCatalogEntry(1, "revision $nonce")), 0)
    }
}
