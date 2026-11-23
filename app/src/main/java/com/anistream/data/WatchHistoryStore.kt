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

class WatchHistoryStore(context: Context) {
    private val file = File(context.filesDir, "watch_history.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    // One list per process: every screen's ViewModel makes its own store, and they must all see the same
    // entries (otherwise Home keeps showing what was there before you watched something).
    private val _entries: MutableStateFlow<List<WatchHistoryEntry>> =
        synchronized(Companion) { shared ?: MutableStateFlow(load()).also { shared = it } }

    private companion object {
        @Volatile var shared: MutableStateFlow<List<WatchHistoryEntry>>? = null
    }

    fun getAll(): StateFlow<List<WatchHistoryEntry>> = _entries.asStateFlow()

    suspend fun getByAnimeId(animeId: String): WatchHistoryEntry? =
        _entries.value.find { it.animeId == animeId }

    suspend fun upsert(entry: WatchHistoryEntry) = withContext(Dispatchers.IO) {
        val current = _entries.value.toMutableList()
        val index = current.indexOfFirst { it.animeId == entry.animeId }
        if (index >= 0) current[index] = entry else current.add(0, entry)
        current.sortByDescending { it.updatedAt }
        save(current)
        _entries.value = current
    }

    suspend fun delete(animeId: String) = withContext(Dispatchers.IO) {
        val current = _entries.value.filter { it.animeId != animeId }
        save(current)
        _entries.value = current
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        save(emptyList())
        _entries.value = emptyList()
    }

    private fun load(): List<WatchHistoryEntry> = try {
        if (file.exists()) json.decodeFromString(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(entries: List<WatchHistoryEntry>) {
        file.writeText(json.encodeToString(entries))
    }
}
