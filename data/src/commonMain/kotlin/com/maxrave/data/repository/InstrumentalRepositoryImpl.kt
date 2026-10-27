package com.maxrave.data.repository

import com.maxrave.data.io.fileSystem
import com.maxrave.domain.data.player.InstrumentalRegistry
import com.maxrave.domain.data.player.InstrumentalState
import com.maxrave.domain.data.player.InstrumentalStep
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.InstrumentalRepository
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import org.simpmusic.aiservice.VocalRemover
import org.simpmusic.aiservice.VocalRemoverException

/**
 * Instrumentals are cached as `<dir>/<videoId>.m4a` and kept until the user clears them in
 * Settings: each one cost an upload and one of the account's daily MVSEP separations.
 */
internal class InstrumentalRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val mediaPlayerHandler: MediaPlayerHandler,
    private val vocalRemover: VocalRemover,
    private val dir: String,
) : InstrumentalRepository {
    // Main, so that [jobs] is only ever touched from one thread; the blocking file work hops to IO.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableMapOf<String, Job>()

    override fun toggle(videoId: String) {
        when (InstrumentalRegistry.states.value[videoId]) {
            is InstrumentalState.On -> {
                InstrumentalRegistry.set(videoId, null)
                scope.launch { reloadIfCurrent(videoId) }
            }
            // A second tap while it is being made cancels it. MVSEP still finishes the job it was
            // sent, so the separation counts against the daily allowance either way.
            is InstrumentalState.Working -> {
                jobs.remove(videoId)?.cancel()
                InstrumentalRegistry.set(videoId, null)
            }
            else -> jobs[videoId] = scope.launch { makeAndPlay(videoId) }
        }
    }

    private suspend fun makeAndPlay(videoId: String) {
        val file = "$dir/$videoId.m4a".toPath()
        try {
            if (!fileSystem().exists(file)) {
                val token =
                    dataStoreManager.mvsepApiToken.first().ifBlank {
                        throw VocalRemoverException("Set your MVSEP API token in Settings > AI first")
                    }
                step(videoId, InstrumentalStep.DOWNLOADING)
                val streamUrl =
                    streamRepository.getStream(dataStoreManager, videoId, isDownloading = true, isVideo = false).lastOrNull()
                        ?: throw VocalRemoverException("Couldn't get this song's audio")
                val audio = vocalRemover.fetchStream(streamUrl)
                step(videoId, InstrumentalStep.UPLOADING)
                val extension = if (streamUrl.contains("mime=audio%2Fmp4")) "m4a" else "webm"
                val hash = vocalRemover.createJob(token, audio, "$videoId.$extension")
                step(videoId, InstrumentalStep.QUEUED)
                val resultUrl =
                    vocalRemover.awaitInstrumentalUrl(hash) { status ->
                        step(videoId, if (status == "waiting") InstrumentalStep.QUEUED else InstrumentalStep.SEPARATING)
                    }
                step(videoId, InstrumentalStep.SAVING)
                val instrumental = vocalRemover.download(resultUrl)
                // Written beside and moved into place, so a half-written file is never taken for a cached one.
                val partial = "$dir/$videoId.m4a.part".toPath()
                withContext(Dispatchers.IO) {
                    fileSystem().createDirectories(dir.toPath())
                    fileSystem().write(partial) { write(instrumental) }
                    fileSystem().atomicMove(partial, file)
                }
            }
            InstrumentalRegistry.set(videoId, InstrumentalState.On(file.toString()))
            reloadIfCurrent(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Vocal removal failed for $videoId: ${e.message}", e)
            InstrumentalRegistry.set(videoId, InstrumentalState.Failed(e.message ?: e::class.simpleName ?: "Unknown error"))
        }
    }

    private fun step(
        videoId: String,
        step: InstrumentalStep,
    ) = InstrumentalRegistry.set(videoId, InstrumentalState.Working(step))

    /** Reloading the track at the same position makes the player's resolver pick the new source. */
    private suspend fun reloadIfCurrent(videoId: String) =
        withContext(Dispatchers.Main) {
            val player = mediaPlayerHandler.player
            if (player.currentMediaItem?.mediaId == videoId) {
                player.seekTo(player.currentMediaItemIndex, player.currentPosition)
            }
        }

    override suspend fun getCacheSize(): Long =
        withContext(Dispatchers.IO) {
            val path = dir.toPath()
            if (!fileSystem().exists(path)) return@withContext 0L
            fileSystem().list(path).sumOf { fileSystem().metadataOrNull(it)?.size ?: 0L }
        }

    override suspend fun clearCache() =
        withContext(Dispatchers.Main) {
            val playing = InstrumentalRegistry.states.value.filterValues { it is InstrumentalState.On }.keys
            InstrumentalRegistry.clear()
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            playing.forEach { reloadIfCurrent(it) }
            withContext(Dispatchers.IO) { fileSystem().deleteRecursively(dir.toPath()) }
        }

    companion object {
        private const val TAG = "Instrumental"
    }
}
