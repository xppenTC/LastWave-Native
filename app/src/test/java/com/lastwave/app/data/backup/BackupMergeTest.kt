package com.lastwave.app.data.backup

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.StoredTrack
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/** Same cases as LastWave Desktop's backup_sync_test.dart. */
class BackupMergeTest {

    private fun t(name: String, url: String = "") = StoredTrack(name = name, artist = "A", url = url)

    private fun pl(
        id: Long,
        tracks: List<StoredTrack>,
        title: String = "P",
        mode: String = "custom",
        pinned: Boolean = false,
    ) = BackupPlaylistSnapshot(
        id = id,
        title = title,
        subtitle = "",
        mode = mode,
        tracksJson = Json.encodeToString(tracks),
        createdAtMillis = id,
        isPinned = pinned,
    )

    private fun file(
        playlists: List<BackupPlaylistSnapshot>,
        exclusions: List<String> = emptyList(),
        schema: Int = 8,
    ) = BackupFile(
        schemaVersion = schema,
        createdAt = 0,
        appVersion = "test",
        prefs = BackupPrefsSnapshot(),
        playlists = playlists,
        recommendationExclusions = exclusions.map { BackupRecommendationExclusionSnapshot(it, 0) },
    )

    private fun names(p: BackupPlaylistSnapshot) = BackupMerge.tracksOf(p).map { it.name }

    @Test
    fun `first sync keeps everything - file order, then local additions`() {
        val m = BackupMerge.merge(
            base = null,
            local = file(listOf(pl(1, listOf(t("a"), t("c"))), pl(2, listOf(t("x"))))),
            remote = file(listOf(pl(1, listOf(t("a"), t("b"))), pl(3, listOf(t("y"))))),
        )
        assertThat(m.playlists.map { it.id }).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(names(m.playlists.first())).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `tracks added above every shared one stay first`() {
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a"), t("b")), mode = "liked"))),
            local = file(listOf(pl(1, listOf(t("n2"), t("n1"), t("a"), t("b"), t("c")), mode = "liked"))),
            remote = file(listOf(pl(1, listOf(t("a"), t("b"), t("r")), mode = "liked"))),
        )
        assertThat(names(m.playlists.single())).containsExactly("n2", "n1", "a", "b", "r", "c").inOrder()
    }

    @Test
    fun `a track moved or re-liked here keeps its place when the file kept its order`() {
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a"), t("b"), t("x")), mode = "liked"))),
            local = file(listOf(pl(1, listOf(t("x"), t("a"), t("b")), mode = "liked"))),
            remote = file(listOf(pl(1, listOf(t("a"), t("r"), t("b"), t("x")), mode = "liked"))),
        )
        assertThat(names(m.playlists.single())).containsExactly("x", "a", "r", "b").inOrder()
    }

    @Test
    fun `the file's order wins when both sides reordered`() {
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a"), t("b"), t("c"))))),
            local = file(listOf(pl(1, listOf(t("c"), t("a"), t("b"))))),
            remote = file(listOf(pl(1, listOf(t("b"), t("a"), t("c"))))),
        )
        assertThat(names(m.playlists.single())).containsExactly("b", "a", "c").inOrder()
    }

    @Test
    fun `a track removed here and one added there both apply`() {
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a"), t("b"))))),
            local = file(listOf(pl(1, listOf(t("a"))))),
            remote = file(listOf(pl(1, listOf(t("a"), t("b"), t("c"))))),
        )
        assertThat(names(m.playlists.single())).containsExactly("a", "c").inOrder()
    }

    @Test
    fun `a track removed there stays removed`() {
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a"), t("b"))))),
            local = file(listOf(pl(1, listOf(t("a"), t("b"))))),
            remote = file(listOf(pl(1, listOf(t("a"))))),
        )
        assertThat(names(m.playlists.single())).containsExactly("a")
    }

    @Test
    fun `deleted playlists go, unless the other side edited them`() {
        val base = file(listOf(pl(1, listOf(t("a"))), pl(2, listOf(t("b")))))
        val m = BackupMerge.merge(
            base = base,
            local = file(listOf(pl(2, listOf(t("b"), t("c"))))),
            remote = file(listOf(pl(1, listOf(t("a"))))),
        )
        assertThat(m.playlists.map { it.id }).containsExactly(2L)

        val edited = BackupMerge.merge(
            base = base,
            local = file(listOf(pl(2, listOf(t("b"))))),
            remote = file(listOf(pl(1, listOf(t("a"), t("z"))), pl(2, listOf(t("b"))))),
        )
        assertThat(edited.playlists.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `renames - ours wins when we changed it, theirs otherwise`() {
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, emptyList(), title = "Old"), pl(2, emptyList(), title = "Old"))),
            local = file(listOf(pl(1, emptyList(), title = "Mine"), pl(2, emptyList(), title = "Old", pinned = true))),
            remote = file(listOf(pl(1, emptyList(), title = "Theirs"), pl(2, emptyList(), title = "Theirs"))),
        )
        assertThat(m.playlists[0].title).isEqualTo("Mine")
        assertThat(m.playlists[1].title).isEqualTo("Theirs")
        assertThat(m.playlists[1].isPinned).isTrue()
    }

    @Test
    fun `Liked Songs match by mode whatever their ids`() {
        val m = BackupMerge.merge(
            base = null,
            local = file(listOf(pl(10, listOf(t("a")), mode = "liked"))),
            remote = file(listOf(pl(20, listOf(t("b")), mode = "liked"))),
        )
        assertThat(m.playlists.single().id).isEqualTo(20L)
        assertThat(names(m.playlists.single())).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `tracks this device cannot hold are never read as deleted`() {
        val phoneSong = t("mine", "/storage/emulated/0/Music/mine.flac")
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a"), phoneSong)))),
            local = file(listOf(pl(1, listOf(t("a"), t("b"))))),
            remote = file(listOf(pl(1, listOf(t("a"), phoneSong)))),
            holds = { !it.url.orEmpty().startsWith("/") },
        )
        assertThat(names(m.playlists.single())).containsExactly("a", "mine", "b").inOrder()
    }

    @Test
    fun `exclusions merge the same way`() {
        val m = BackupMerge.merge(
            base = file(emptyList(), listOf("a|x", "b|x")),
            local = file(emptyList(), listOf("b|x", "c|x")),
            remote = file(emptyList(), listOf("a|x", "b|x", "d|x")),
        )
        assertThat(m.exclusions.map { it.trackKey }).containsExactly("b|x", "d|x", "c|x").inOrder()
    }

    @Test
    fun `a schema 6 file keeps the exclusions made here`() {
        val m = BackupMerge.merge(
            base = file(emptyList(), listOf("a|x")),
            local = file(emptyList(), listOf("a|x", "b|x")),
            remote = file(emptyList(), schema = 6),
        )
        assertThat(m.exclusions.map { it.trackKey }).containsExactly("a|x", "b|x")
    }

    @Test
    fun `a shared track edited here keeps our copy and goes to the file`() {
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a", "old"), t("b"))))),
            local = file(listOf(pl(1, listOf(t("a", "pinned"), t("b"))))),
            remote = file(listOf(pl(1, listOf(t("a", "old"), t("b"))))),
        )
        assertThat(BackupMerge.tracksOf(m.playlists.single()).map { it.url }).containsExactly("pinned", "").inOrder()
    }

    @Test
    fun `a file copy without a link gets ours back`() {
        val link = "khinsider://track?page=%2Fx.mp3"
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a", link))))),
            local = file(listOf(pl(1, listOf(t("a", link))))),
            remote = file(listOf(pl(1, listOf(t("a"))))),
        )
        assertThat(BackupMerge.tracksOf(m.playlists.single()).single().url).isEqualTo(link)
    }

    @Test
    fun `spotify session never leaves the phone`() {
        for (key in listOf(
            "ytm_cookies", "lw_apikey", "lw_apisecret", "lw_sessionkey", "lw_addon_url", "lw_http_addons_json",
            "spotify_sp_dc", "spotify_personal_token", "spotify_client_token",
        )) {
            assertThat(isSensitivePreference(key)).isTrue()
        }
        assertThat(isSensitivePreference("theme")).isFalse()
    }

    /** The old quadratic inLocalOrder: the linked-list one must give the same order. */
    private fun oldInLocalOrder(merged: List<StoredTrack>, local: List<StoredTrack>, remote: List<StoredTrack>): List<StoredTrack> {
        val byKey = merged.associateBy(BackupMerge::trackKey)
        val outKeys = local.map(BackupMerge::trackKey).filter { it in byKey }.distinct().toMutableList()
        var after = -1
        for (key in remote.map(BackupMerge::trackKey)) {
            val at = outKeys.indexOf(key)
            if (at >= 0) after = at else if (key in byKey) outKeys.add(++after, key)
        }
        return outKeys.map(byKey::getValue)
    }

    @Test
    fun `linear track order matches the old one`() {
        val random = java.util.Random(42)
        repeat(2_000) {
            fun some() = List(random.nextInt(12)) { t("t${random.nextInt(15)}") }
            val merged = some()
            val local = some()
            val remote = some()
            assertThat(BackupMerge.inLocalOrder(merged, local, remote)).isEqualTo(oldInLocalOrder(merged, local, remote))
        }
    }

    @Test
    fun `a desktop's Local songs is dropped, the phone's stays`() {
        val phone = pl(1, listOf(t("mine", "/storage/emulated/0/Music/a.flac")), title = "Local songs", mode = "local")
        val desktop = pl(2, listOf(t("pc")), title = "Local songs", mode = "local")
        val m = BackupMerge.merge(base = null, local = file(listOf(phone, desktop)), remote = file(listOf(desktop)))
        assertThat(m.playlists.map { it.id }).containsExactly(1L)
    }
}
