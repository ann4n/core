package com.maxrave.domain.repository

/**
 * Plays songs without their vocals, by sending them to an AI vocal remover (MVSEP) and caching
 * the instrumental it returns. Progress and the on/off state are in [com.maxrave.domain.data.player.InstrumentalRegistry].
 */
interface InstrumentalRepository {
    /**
     * Turns the instrumental on for [videoId], making it first if it is not cached, or turns it
     * off again. The current song reloads at the same position either way.
     */
    fun toggle(videoId: String)

    suspend fun getCacheSize(): Long

    /** Deletes every cached instrumental and plays the affected songs with their vocals again. */
    suspend fun clearCache()
}
