package com.lastwave.app.ui.search.khinsider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.khinsider.KhAlbum
import com.lastwave.app.data.khinsider.KhFormat
import com.lastwave.app.data.khinsider.KhInsiderClient
import com.lastwave.app.data.khinsider.KhInsiderParser
import com.lastwave.app.data.khinsider.KhTrack
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KhAlbumUiState(
    val albumId: String = "",
    val loading: Boolean = false,
    val album: KhAlbum? = null,
    val error: String? = null,
)

@HiltViewModel
class KhInsiderAlbumViewModel @Inject constructor(
    private val client: KhInsiderClient,
    private val musicPlayer: MusicPlayer,
) : ViewModel() {
    private val _state = MutableStateFlow(KhAlbumUiState())
    val state: StateFlow<KhAlbumUiState> = _state.asStateFlow()
    val format: StateFlow<KhFormat> = client.format
    private var loadJob: Job? = null

    fun open(albumId: String, force: Boolean = false) {
        if (albumId.isBlank()) return
        val current = _state.value
        if (!force && current.albumId == albumId && (current.album != null || current.loading)) return
        loadJob?.cancel()
        _state.value = KhAlbumUiState(albumId = albumId, loading = true)
        loadJob = viewModelScope.launch {
            try {
                val album = client.album(albumId, forceRefresh = force)
                _state.value = KhAlbumUiState(albumId = albumId, album = album)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _state.value = KhAlbumUiState(albumId = albumId, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun setFormat(format: KhFormat) = client.setFormat(format)

    fun play(startIndex: Int = 0, shuffled: Boolean = false) {
        val album = _state.value.album ?: return
        val queue = album.tracks.map { it.toPlayable(album) }
        if (queue.isEmpty()) return
        musicPlayer.playQueue(
            tracks = queue,
            startIndex = startIndex.coerceIn(0, queue.lastIndex),
            sourceLabel = SOURCE_LABEL,
            startShuffled = shuffled,
        )
    }

    companion object {
        const val SOURCE_LABEL = "KHInsider"

        fun KhTrack.toPlayable(album: KhAlbum): PlayableTrack = PlayableTrack(
            title = name,
            // KHInsider lists no per-track artist: the game/album stands in.
            artist = album.name,
            album = album.name,
            artworkUrl = album.coverUrl,
            playbackUrl = KhInsiderParser.playbackUri(pagePath),
            durationMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L),
        )
    }
}
