package com.maxrave.domain.data.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where a song's vocal removal stands. A song with no entry plays with its vocals. */
sealed interface InstrumentalState {
    /** The instrumental is being made; [step] says which part of it is running. */
    data class Working(
        val step: InstrumentalStep,
    ) : InstrumentalState

    /** The song plays as the instrumental stored at [path]. */
    data class On(
        val path: String,
    ) : InstrumentalState

    data class Failed(
        val message: String,
    ) : InstrumentalState
}

enum class InstrumentalStep { DOWNLOADING, UPLOADING, QUEUED, SEPARATING, SAVING }

/**
 * The songs playing as their instrumental in this run of the app, written by the instrumental
 * repository and read by both players' source resolvers, which open [pathFor] instead of the stream.
 *
 * In memory on purpose, like [LiveStreamRegistry]: the instrumental file itself is cached on disk,
 * but turning it on is a choice for this listen, so a restart plays every song with its vocals again.
 */
object InstrumentalRegistry {
    private val _states = MutableStateFlow<Map<String, InstrumentalState>>(emptyMap())
    val states: StateFlow<Map<String, InstrumentalState>> = _states.asStateFlow()

    fun pathFor(videoId: String): String? = (_states.value[videoId] as? InstrumentalState.On)?.path

    fun set(
        videoId: String,
        state: InstrumentalState?,
    ) = _states.update { if (state == null) it - videoId else it + (videoId to state) }

    fun clear() = _states.update { emptyMap() }
}
