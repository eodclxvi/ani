package com.anistream.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anistream.data.WatchHistoryEntry
import com.anistream.data.WatchHistoryStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = WatchHistoryStore(app)

    val history: StateFlow<List<WatchHistoryEntry>> = store.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(animeId: String) {
        viewModelScope.launch { store.delete(animeId) }
    }

    fun clearAll() {
        viewModelScope.launch { store.deleteAll() }
    }
}
