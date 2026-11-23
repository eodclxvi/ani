package com.anistream.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.data.Episode
import com.anistream.data.FavoritesStore
import com.anistream.data.HiAnimeApi
import com.anistream.data.StreamInfo
import com.anistream.data.WatchHistoryEntry
import com.anistream.data.WatchHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AnimeDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val store = WatchHistoryStore(app)
    private val favStore = FavoritesStore(app)

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite = _isFavorite.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes = _episodes.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _streamInfo = MutableStateFlow<StreamInfo?>(null)
    val streamInfo = _streamInfo.asStateFlow()

    private val _streamLoading = MutableStateFlow(false)
    val streamLoading = _streamLoading.asStateFlow()

    private val _watchHistory = MutableStateFlow<WatchHistoryEntry?>(null)
    val watchHistory = _watchHistory.asStateFlow()

    private val _currentEpisode = MutableStateFlow<Episode?>(null)
    /** The episode being played (or loading), for next/previous in the player. */
    val currentEpisode = _currentEpisode.asStateFlow()

    /** Where the player should start the stream that is loading; it reads this once and resets it to 0. */
    var resumePositionMs: Long = 0L

    private val _isDub = MutableStateFlow(false)
    val isDub = _isDub.asStateFlow()

    var currentAnimeId: String = ""
        private set
    var currentAnimeTitle: String = ""
        private set
    var currentPosterUrl: String? = null
        private set

    fun loadEpisodes(animeId: String, title: String, posterUrl: String?) {
        currentAnimeId = animeId
        currentAnimeTitle = title
        currentPosterUrl = posterUrl
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _episodes.value = HiAnimeApi.getEpisodes(animeId)
                _watchHistory.value = store.getByAnimeId(animeId)
                _isFavorite.value = favStore.isFavorite(animeId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load episodes"
            } finally {
                _loading.value = false
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            favStore.toggle(currentAnimeId, currentAnimeTitle, currentPosterUrl)
            _isFavorite.value = favStore.isFavorite(currentAnimeId)
        }
    }

    fun toggleDub() {
        _isDub.value = !_isDub.value
    }

    fun playEpisode(
        episode: Episode,
        mode: String = if (_isDub.value) "dub" else "sub",
        resumeMs: Long = 0L,
    ) {
        _currentEpisode.value = episode
        resumePositionMs = resumeMs
        viewModelScope.launch {
            val previous = store.getByAnimeId(currentAnimeId)
            _streamLoading.value = true
            _streamInfo.value = null
            _error.value = null
            try {
                val info = HiAnimeApi.getStreamUrl(episode.dataId, mode)
                _streamInfo.value = info
                store.upsert(
                    WatchHistoryEntry(
                        animeId = currentAnimeId,
                        animeTitle = currentAnimeTitle,
                        posterUrl = currentPosterUrl,
                        lastEpisode = episode.number,
                        lastEpisodeDataId = episode.dataId,
                        positionMs = resumeMs,
                        durationMs = if (previous?.lastEpisodeDataId == episode.dataId) previous.durationMs else 0L,
                    )
                )
                _watchHistory.value = store.getByAnimeId(currentAnimeId)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load stream"
            } finally {
                _streamLoading.value = false
            }
        }
    }

    fun updatePlaybackPosition(positionMs: Long, durationMs: Long) {
        viewModelScope.launch {
            val existing = store.getByAnimeId(currentAnimeId) ?: return@launch
            store.upsert(existing.copy(positionMs = positionMs, durationMs = durationMs, updatedAt = System.currentTimeMillis()))
            _watchHistory.value = store.getByAnimeId(currentAnimeId)
        }
    }

    fun clearStreamInfo() {
        _streamInfo.value = null
    }
}
