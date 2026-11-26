package com.anistream.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Playback settings, saved on the device. Shared by the phone player, the TV player and casting. */
object PlaybackPrefs {
    private const val FILE = "ani_prefs"
    private const val AUTOPLAY = "autoplay_next"

    /** Start the next episode by itself when one ends. On by default. */
    var autoplayNext by mutableStateOf(true)
        private set

    fun load(context: Context) {
        autoplayNext = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(AUTOPLAY, true)
    }

    fun setAutoplayNext(context: Context, on: Boolean) {
        autoplayNext = on
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(AUTOPLAY, on).apply()
    }
}
