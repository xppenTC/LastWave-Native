package com.lastwave.app.data.backup

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.lastwave.app.BuildConfig
import com.lastwave.app.data.playlist.PlaylistRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Automatic sync with LastWave Desktop through the backup file kept in the
 * user's own Google Drive, in the hidden app-data folder (`drive.appdata`:
 * the app sees nothing else in the Drive). Same folder, file name and
 * merge ([BackupRepository.sync]) as the desktop's `drive_sync.dart`.
 *
 * Google matches this app by package name and signing certificate (an
 * Android OAuth client in the same Cloud project as the desktop client),
 * so no secret ships in the APK. Sync runs at launch, 30 s after a
 * playlist change, every 10 minutes and on "Sync now".
 */
@Singleton
class DriveSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val playlistRepository: PlaylistRepository,
    private val http: OkHttpClient,
    private val applicationScope: CoroutineScope,
) {
    data class State(
        val connected: Boolean = false,
        val syncing: Boolean = false,
        val lastSyncMillis: Long? = null,
        val error: String? = null,
    )

    private class StaleFile : IOException("The file kept changing; try again.")

    // Not in DataStore: everything there is written into backups.
    private val prefs = context.getSharedPreferences("drive_sync", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State(connected = prefs.getBoolean(KEY_CONNECTED, false)))
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()
    private var started = false
    private val json = Json { ignoreUnknownKeys = true }

    val authorizationRequest: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_APPDATA)))
        .build()

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        applicationScope.launch {
            playlistRepository.changes.debounce(CHANGE_DEBOUNCE_MS).collect { syncNow() }
        }
        applicationScope.launch {
            while (true) {
                syncNow()
                delay(HEARTBEAT_MS)
            }
        }
    }

    /** Asks Google for Drive access; a result with a resolution needs the consent UI. */
    suspend fun authorize(): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(authorizationRequest).await()

    fun resultFromConsent(data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    /** Access granted (directly or after the consent screen): link and sync. */
    fun onConnected() {
        prefs.edit().putBoolean(KEY_CONNECTED, true).apply()
        _state.update { it.copy(connected = true, error = null) }
        applicationScope.launch { syncNow() }
    }

    fun disconnect() {
        prefs.edit().putBoolean(KEY_CONNECTED, false).apply()
        _state.value = State()
    }

    suspend fun syncNow() {
        if (!_state.value.connected || mutex.isLocked) return
        mutex.withLock {
            _state.update { it.copy(syncing = true, error = null) }
            val error = runCatching {
                val result = authorize()
                val token = result.accessToken
                if (result.hasResolution() || token == null) {
                    throw IOException("Google Drive needs you to connect again")
                }
                // Another device may write between our read and our write: the
                // version check makes us read and merge again instead of losing it.
                var attempt = 0
                while (true) {
                    try {
                        syncOnce(token)
                        break
                    } catch (stale: StaleFile) {
                        if (++attempt > 2) throw stale
                    }
                }
            }.exceptionOrNull()
            _state.update {
                it.copy(
                    syncing = false,
                    lastSyncMillis = if (error == null) System.currentTimeMillis() else it.lastSyncMillis,
                    error = error?.let { e -> e.message ?: e.javaClass.simpleName },
                )
            }
        }
    }

    private suspend fun syncOnce(token: String) = withContext(Dispatchers.IO) {
        val listUrl = "$DRIVE/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$FILE_NAME'")
            .addQueryParameter("fields", "files(id,version)")
            .build()
        val files = jsonOf(call(Request.Builder().url(listUrl), token))["files"]?.jsonArray.orEmpty()
        val result = if (files.isEmpty()) {
            // First device to sync: the file starts as this library.
            backupRepository.sync(backupRepository.buildBackup(BuildConfig.VERSION_NAME), BuildConfig.VERSION_NAME) {
                createFile(token, it)
            }
        } else {
            val file = files.first().jsonObject
            val id = file["id"]!!.jsonPrimitive.content
            val version = file["version"]?.jsonPrimitive?.content
            val content = call(Request.Builder().url("$DRIVE/files/$id?alt=media"), token)
            backupRepository.sync(content, BuildConfig.VERSION_NAME) { merged ->
                val now = jsonOf(call(Request.Builder().url("$DRIVE/files/$id?fields=version"), token))
                if (now["version"]?.jsonPrimitive?.content != version) throw StaleFile()
                // shortcut: a write landing between this check and the upload is
                // still overwritten; Drive v3 has no conditional upload.
                call(
                    Request.Builder().url("$UPLOAD/files/$id?uploadType=media")
                        .patch(merged.toRequestBody(JSON_TYPE)),
                    token,
                )
            }
        }
        when (result) {
            is RestoreResult.Success -> Unit
            RestoreResult.UnsupportedSchema -> throw IOException("The Drive file comes from a newer LastWave")
            RestoreResult.InvalidFile -> throw IOException("The Drive file is not a LastWave backup")
            is RestoreResult.Failed -> throw IOException(result.message)
        }
    }

    private fun createFile(token: String, content: String) {
        val boundary = "lastwave-sync-boundary"
        val body = "--$boundary\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
            "{\"name\":\"$FILE_NAME\",\"parents\":[\"appDataFolder\"]}\r\n" +
            "--$boundary\r\n" +
            "Content-Type: application/json\r\n\r\n" +
            "$content\r\n" +
            "--$boundary--"
        call(
            Request.Builder().url("$UPLOAD/files?uploadType=multipart")
                .post(body.toRequestBody("multipart/related; boundary=$boundary".toMediaType())),
            token,
        )
    }

    private fun call(builder: Request.Builder, token: String): String =
        http.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Google Drive error ${response.code}")
            text
        }

    private fun jsonOf(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    private companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val DRIVE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FILE_NAME = "lastwave-sync.json"
        const val KEY_CONNECTED = "connected"
        const val CHANGE_DEBOUNCE_MS = 30_000L
        const val HEARTBEAT_MS = 10 * 60_000L
        val JSON_TYPE = "application/json".toMediaType()
    }
}
