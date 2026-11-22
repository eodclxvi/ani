package com.anistream.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class FavoritesStore(context: Context) {
    private val file = File(context.filesDir, "favorites.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _entries = MutableStateFlow<List<FavoriteEntry>>(emptyList())

    init {
        _entries.value = load()
    }

    fun getAll(): Flow<List<FavoriteEntry>> = _entries.asStateFlow()

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
