package com.lastwave.app.ui.feed

/** Words YouTube Music uses for the listed shelves, English and French (the settings list is English only). */
private val YT_SHELF_ALIASES: Map<String, List<String>> = mapOf(
    "listen again" to listOf("listen again", "jump back", "réécouter"),
    "quick picks" to listOf("quick pick", "picked for you", "sélection rapide"),
    "mixed for you" to listOf("mixed for you", "mixes", "mix pour vous"),
    "trending" to listOf("trend", "tendance"),
    "new releases" to listOf("new release", "fresh drop", "nouveauté"),
    "community playlists" to listOf("community", "communauté"),
    "forgotten favorites" to listOf("forgotten", "oublié"),
    "similar to" to listOf("similar"),
    "from your library" to listOf("library", "bibliothèque"),
)

/** True when the shelf [title] is the listed YouTube Music section [listed]. */
internal fun ytShelfMatches(title: String, listed: String): Boolean {
    val t = title.trim().lowercase()
    val h = listed.trim().lowercase()
    return t == h || t.startsWith(h) || h.startsWith(t) || YT_SHELF_ALIASES[h].orEmpty().any { t.contains(it) }
}

/** Hidden when its listed section is unchecked. */
internal fun ytShelfHidden(title: String, hidden: Set<String>): Boolean =
    hidden.any { ytShelfMatches(title, it) }
