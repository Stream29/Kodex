package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue

/** Frontend-only display choices, owned by one retained History widget. */
internal class AssistantMarkdownModes {
    private data class StoredKey(val generation: Long, val index: Int)

    private val stored = mutableStateMapOf<StoredKey, Boolean>()
    private var streamIdentity by mutableStateOf<Any?>(null, referentialEqualityPolicy())
    private var streamGeneration by mutableStateOf<Long?>(null)
    private var streamRaw by mutableStateOf(false)
    private var streamEligible by mutableStateOf(false)

    fun storedIsRaw(generation: Long, index: Int): Boolean =
        stored[StoredKey(generation, index)] == true

    fun toggleStored(generation: Long, index: Int) {
        val key = StoredKey(generation, index)
        if (stored[key] == true) stored.remove(key) else stored[key] = true
    }

    fun retainGeneration(generation: Long) {
        stored.keys.removeAll { it.generation != generation }
        if (streamGeneration != generation) clearStream()
    }

    fun streamIsRaw(generation: Long, identity: Any): Boolean =
        streamIsEligible(generation, identity) && streamRaw

    fun streamIsEligible(generation: Long, identity: Any): Boolean =
        streamGeneration == generation && streamIdentity === identity && streamEligible

    fun reportStreamEligibility(generation: Long, identity: Any, eligible: Boolean) {
        if (streamGeneration != generation || streamIdentity !== identity) {
            streamGeneration = generation
            streamIdentity = identity
            streamRaw = false
        }
        streamEligible = eligible
        if (!eligible) streamRaw = false
    }

    fun toggleStream(generation: Long, identity: Any) {
        if (streamIsEligible(generation, identity)) streamRaw = !streamRaw
    }

    fun retainStream(generation: Long, identity: Any?) {
        if (streamGeneration != generation || streamIdentity !== identity) clearStream()
    }

    private fun clearStream() {
        streamIdentity = null
        streamGeneration = null
        streamRaw = false
        streamEligible = false
    }
}
