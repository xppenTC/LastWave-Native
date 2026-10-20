package com.lastwave.app.ui.search.khinsider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.khinsider.KhAlbum
import com.lastwave.app.data.khinsider.KhFormat
import com.lastwave.app.data.khinsider.KhInsiderParser
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.player.LocalMusicPlayer

/** Full-screen KHInsider album: cover, format choice, play / shuffle, track list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KhInsiderAlbumDialog(
    albumId: String,
    fallbackTitle: String,
    fallbackArtwork: String?,
    onDismiss: () -> Unit,
    viewModel: KhInsiderAlbumViewModel = hiltViewModel(),
) {
    LaunchedEffect(albumId) { viewModel.open(albumId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val format by viewModel.format.collectAsStateWithLifecycle()
    val chrome by LocalMusicPlayer.current.chromeState.collectAsStateWithLifecycle()
    val playingUrl = chrome.current?.playbackUrl?.takeIf { KhInsiderParser.isPlaybackUri(it) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.khinsider_back))
                    }
                    Text(
                        state.album?.name ?: fallbackTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                val album = state.album
                when {
                    album != null -> AlbumContent(
                        album = album,
                        fallbackArtwork = fallbackArtwork,
                        format = format,
                        playingUrl = playingUrl,
                        onFormat = viewModel::setFormat,
                        onPlay = { index, shuffled -> viewModel.play(index, shuffled) },
                    )
                    state.error != null -> Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.khinsider_error, state.error.orEmpty()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.open(albumId, force = true) }) {
                            Text(stringResource(R.string.khinsider_retry))
                        }
                    }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ExpressiveLoadingIndicator()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumContent(
    album: KhAlbum,
    fallbackArtwork: String?,
    format: KhFormat,
    playingUrl: String?,
    onFormat: (KhFormat) -> Unit,
    onPlay: (index: Int, shuffled: Boolean) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtworkImage(
                        name = album.name,
                        artist = album.name,
                        embeddedUrl = album.coverUrl ?: fallbackArtwork,
                        fallbackIcon = Icons.Filled.SportsEsports,
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(16.dp)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            album.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(
                            album.platforms.takeIf { it.isNotEmpty() }?.joinToString(", "),
                            album.year,
                            album.type,
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        (album.developer ?: album.publisher)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            stringResource(R.string.khinsider_track_count, album.tracks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.khinsider_format_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    KhFormat.entries.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = format == option,
                            onClick = { onFormat(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = KhFormat.entries.size),
                        ) { Text(option.name) }
                    }
                }
                if (format == KhFormat.FLAC && !album.hasFlac) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.khinsider_no_flac),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onPlay(0, false) }, enabled = album.tracks.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.khinsider_play))
                    }
                    FilledTonalButton(onClick = { onPlay(0, true) }, enabled = album.tracks.size > 1, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.khinsider_shuffle))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        val multiDisc = album.tracks.mapNotNull { it.disc }.distinct().size > 1
        itemsIndexed(album.tracks, key = { _, t -> t.pagePath }) { index, track ->
            val playing = playingUrl == KhInsiderParser.playbackUri(track.pagePath)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(index, false) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        if (multiDisc && track.disc != null) append("${track.disc}-")
                        append(track.number ?: (index + 1))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
                Text(
                    track.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                track.durationSeconds?.let { secs ->
                    Text(
                        "%d:%02d".format(secs / 60, secs % 60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "attribution") {
            Text(
                stringResource(R.string.khinsider_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
