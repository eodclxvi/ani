package com.anistream.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class FavoritesStore(context: Context) {
    private val file = File(context.filesDir, "favorites.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    // One list per process: every screen's ViewModel makes its own store, and they must all see the same
    // entries (otherwise Home keeps showing what was there before you watched something).
    private val _entries: MutableStateFlow<List<FavoriteEntry>> =
        synchronized(Companion) { shared ?: MutableStateFlow(load()).also { shared = it } }

    private companion object {
        @Volatile var shared: MutableStateFlow<List<FavoriteEntry>>? = null
    }

    fun getAll(): StateFlow<List<FavoriteEntry>> = _entries.asStateFlow()

    fun isFavorite(animeId: String): Boolean = _entries.value.any { it.animeId == animeId }

    suspend fun toggle(animeId: String, title: String, posterUrl: String?) = withContext(Dispatchers.IO) {
        val current = _entries.value.toMutableList()
        val index = current.indexOfFirst { it.animeId == animeId }
        if (index >= 0) {
            current.removeAt(index)
        } else {
            current.add(0, FavoriteEntry(animeId = animeId, animeTitle = title, posterUrl = posterUrl))
        }
        save(current)
        _entries.value = current
    }

    private fun load(): List<FavoriteEntry> = try {
        if (file.exists()) json.decodeFromString(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(entries: List<FavoriteEntry>) {
        file.writeText(json.encodeToString(entries))
    }
}
