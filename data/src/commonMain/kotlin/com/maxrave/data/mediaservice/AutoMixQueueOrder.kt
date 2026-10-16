package com.maxrave.data.mediaservice

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.mediaservice.handler.AutoMixPick
import com.maxrave.domain.mediaservice.handler.AutoMixPick.AudioMeta
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.RadioQueueTrim
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "AutoMixOrder"

/**
 * Fork: with AutoMix on (crossfade + Auto duration), each time a radio track starts, weighs the
 * next [AutoMixPick.WINDOW] tracks' tempo and key against it and moves the best fit to play next.
 *
 * Radio only ([RadioQueueTrim.appliesTo]): a playlist or album keeps the order its owner chose.
 * Not with shuffle (the positions then mean another order) nor in a Listen Together room (crossfade
 * is off there). BPM and key come from the stream's stored format when the track has played before,
 * otherwise from the same Tidal search stream resolution runs.
 */
internal class AutoMixQueueOrder(
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val youTube: YouTube,
    /** The handler's scope: the move must run where the handler's own queue edits run. */
    private val scope: CoroutineScope,
) {
    // Written and read only on [scope]'s thread; the lookups themselves run on IO.
    // ponytail: unbounded, one small entry per track heard this run; a radio stays in the hundreds.
    private val metaCache = mutableMapOf<String, AudioMeta?>()
    private var job: Job? = null

    fun onTrackChanged(handler: MediaPlayerHandler) {
        job?.cancel()
        job = scope.launch { reorder(handler) }
    }

    private suspend fun reorder(handler: MediaPlayerHandler) {
        val player = handler.player
        if (player.shuffleModeEnabled || player.crossfadeSuppressed) return
        if (dataStoreManager.crossfadeEnabled.first() != TRUE) return
        if (dataStoreManager.crossfadeDuration.first() != DataStoreManager.CROSSFADE_DURATION_AUTO) return
        val data = handler.queueData.value?.data ?: return
        if (!RadioQueueTrim.appliesTo(data.playlistType, data.playlistId)) {
            Logger.d(TAG, "Not a radio (${data.playlistType} ${data.playlistId}), order kept")
            return
        }
        val currentIndex = handler.currentOrderIndex()
        val current = data.listTracks.getOrNull(currentIndex) ?: return
        val upcoming = data.listTracks.drop(currentIndex + 1).take(AutoMixPick.WINDOW)
        if (upcoming.size < 2) return

        val tracks = listOf(current) + upcoming
        val missing = tracks.filter { it.videoId !in metaCache }.distinctBy { it.videoId }
        val found = withContext(Dispatchers.IO) { missing.map { async { it.videoId to lookUp(it) } }.awaitAll() }
        metaCache.putAll(found)

        val currentMeta = metaCache[current.videoId]
        Logger.d(
            TAG,
            "Weighing after ${current.title} (${currentMeta?.bpm} ${currentMeta?.key}): " +
                upcoming.joinToString { t ->
                    val cost = currentMeta?.let { c -> metaCache[t.videoId]?.let { AutoMixPick.cost(c, it) } }
                    "${t.title}=${cost?.let { (it * 10).roundToInt() / 10.0 } ?: "?"}"
                },
        )
        val pick = AutoMixPick.pick(currentMeta, upcoming.map { metaCache[it.videoId] }) ?: return
        // The lookups took a while: act only if the queue still reads the way it was weighed.
        val nowTracks = handler.queueData.value?.data?.listTracks ?: return
        val nowIndex = handler.currentOrderIndex()
        val position = nowIndex + 1 + pick
        if (nowTracks.getOrNull(nowIndex)?.videoId != current.videoId) return
        if (nowTracks.getOrNull(position)?.videoId != upcoming[pick].videoId) return
        Logger.i(TAG, "Play next for a better mix: ${upcoming[pick].title} (was ${upcoming.first().title})")
        handler.moveItemToPlayNext(position)
    }

    private suspend fun lookUp(track: Track): AudioMeta? {
        streamRepository.getNewFormat(track.videoId).firstOrNull()?.let { format ->
            if (format.bpm != null) return AudioMeta(format.bpm, format.musicKey, format.keyScale)
        }
        val duration = track.durationSeconds?.takeIf { it > 0 } ?: return null
        val artists = track.artists?.joinToString(" ") { it.name }.orEmpty()
        return youTube
            .searchTidalMetadata(tidalSearchQuery(track.title, artists), duration)
            .getOrNull()
            ?.let { AudioMeta(it.bpm, it.musicKey, it.keyScale) }
    }
}

/** A title and artist cleaned into a Tidal search query, as stream resolution sends it. */
internal fun tidalSearchQuery(
    title: String,
    author: String,
): String =
    "$title $author"
        .replace(
            Regex("\\((feat\\.|ft.|cùng với|con|mukana|com|avec|合作音乐人: ) "),
            " ",
        ).replace(
            Regex("( và | & | и | e | und |, |和| dan)"),
            " ",
        ).replace("  ", " ")
        .replace(Regex("([()])"), "")
        .replace(".", " ")
        .replace("  ", " ")
