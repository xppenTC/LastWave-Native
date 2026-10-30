package com.lastwave.app.playback

import com.lastwave.app.data.lyrics.LyricLine

/** Synced lyric line being sung at [positionMs], or null before the first line. */
internal fun lyricLineAt(lines: List<LyricLine>, positionMs: Long): String? =
    lines.lastOrNull { it.timeMs <= positionMs }?.text?.trim()?.ifBlank { null }

/** Second line of the car's now-playing screen: the lyric when one is sung, else artist · quality badge. */
internal fun carSubtitle(artist: String, badge: String?, lyric: String?): String =
    lyric ?: listOfNotNull(artist.ifBlank { null }, badge?.takeUnless { it.isBlank() || it == "AUDIO" })
        .joinToString(" · ")
