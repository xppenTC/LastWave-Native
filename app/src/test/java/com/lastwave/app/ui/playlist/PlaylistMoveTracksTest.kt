package com.lastwave.app.ui.playlist

import android.app.Application
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.playlist.LIKED_SONGS_MODE
import com.lastwave.app.data.playlist.LikedSongsManager
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.playlist.SavedPlaylist
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Moving songs adds them to the target first, then removes them from the source. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class PlaylistMoveTracksTest {

    private val a = GeneratedTrack(name = "A", artist = "X")
    private val b = GeneratedTrack(name = "B", artist = "X")
    private val c = GeneratedTrack(name = "C", artist = "Y")
    private val import = SavedPlaylist(1L, "Import", "", "custom", listOf(a, b, c), 0L)
    private val liked = SavedPlaylist(2L, "Liked Songs", "", LIKED_SONGS_MODE, emptyList(), 0L)
    private val mix = SavedPlaylist(3L, "Mix", "", "custom", emptyList(), 0L)

    private val playlistRepository = mockk<PlaylistRepository>(relaxed = true)
    private val likedSongsManager = mockk<LikedSongsManager>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { playlistRepository.getById(1L) } returns import
        coEvery { playlistRepository.getById(2L) } returns liked
        coEvery { playlistRepository.getById(3L) } returns mix
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PlaylistViewModel(
        playlistRepository = playlistRepository,
        generateRepository = mockk(relaxed = true),
        artworkRepository = mockk(relaxed = true),
        authRepository = mockk(relaxed = true),
        fileExportHelper = mockk(relaxed = true),
        generationStatus = mockk(relaxed = true),
        exportEvents = mockk(relaxed = true),
        ytMusicPreferences = mockk(relaxed = true),
        ytMusicSyncManager = mockk(relaxed = true),
        ytMusicLibraryManager = mockk(relaxed = true),
        trackDownloadManager = mockk(relaxed = true),
        innerTubeApi = mockk(relaxed = true),
        routeNavigator = mockk(relaxed = true),
        localMusicLibrary = mockk(relaxed = true),
        likedSongsManager = likedSongsManager,
    )

    @Test
    fun movingToLikedSongsGoesThroughTheHeartsThenEmptiesTheSource() {
        viewModel().moveTracks(1L, 2L, listOf(a, b, c))

        coVerify { likedSongsManager.mergeTracks(listOf(a, b, c)) }
        coVerify(exactly = 0) { playlistRepository.addTracks(any(), any()) }
        coVerify { playlistRepository.replaceTracksForSync(1L, emptyList(), listOf(a, b, c)) }
    }

    @Test
    fun movingToAnotherPlaylistKeepsTheUnpickedSongs() {
        viewModel().moveTracks(1L, 3L, listOf(b))

        coVerify { playlistRepository.addTracks(3L, listOf(b)) }
        coVerify(exactly = 0) { likedSongsManager.mergeTracks(any()) }
        coVerify { playlistRepository.replaceTracksForSync(1L, listOf(a, c), listOf(a, b, c)) }
    }
}
