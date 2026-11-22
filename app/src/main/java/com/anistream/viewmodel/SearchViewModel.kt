package com.anistream.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.data.AnimeResult
import com.anistream.data.FavoriteEntry
import com.anistream.data.FavoritesStore
import com.anistream.data.HiAnimeApi
import com.anistream.data.WatchHistoryEntry
import com.anistream.data.WatchHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val watchStore = WatchHistoryStore(app)
    private val favStore = FavoritesStore(app)

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _results = MutableStateFlow<List<AnimeResult>>(emptyList())
    val results = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val watchHistory = watchStore.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favorites = favStore.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateQuery(q: String) {
        _query.value = q
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isBlank()) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _results.value = HiAnimeApi.search(q)
                if (_results.value.isEmpty()) _error.value = "No results found"
            } catch (e: Exception) {
                _error.value = e.message ?: "Search failed"
            } finally {
                _loading.value = false
            }
        }
    }
}
