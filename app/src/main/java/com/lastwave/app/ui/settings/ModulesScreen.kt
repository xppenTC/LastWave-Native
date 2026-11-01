package com.lastwave.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.plugin.InstalledProviderModule

import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lastwave.app.data.addon.AddonHealth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Slider
import com.lastwave.app.data.local.ADDON_SWITCH_SECONDS_RANGE
import kotlin.math.roundToInt

/**
 * Settings -> Provider Modules & Addons.
 * Configure external HTTP Addons or install .lwp packages, toggle, remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    onBack: () -> Unit = {},
    viewModel: ModulesViewModel = hiltViewModel(),
) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val preferModules by viewModel.preferModules.collectAsStateWithLifecycle()
    val httpAddons by viewModel.httpAddons.collectAsStateWithLifecycle()
    val addonHealthMap by viewModel.addonHealthMap.collectAsStateWithLifecycle()
    val addonSwitchSeconds by viewModel.addonSwitchSeconds.collectAsStateWithLifecycle()
    val directSourceStream by viewModel.directSourceStream.collectAsStateWithLifecycle()
    val draftHealth by viewModel.draftHealth.collectAsStateWithLifecycle()

    var showAddonDialog by remember { mutableStateOf(false) }
    var editingAddonId by remember { mutableStateOf<String?>(null) }
    var addonInputText by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    // "*/*": providers misreport .lwp as octet-stream; real validation
    // happens in ModuleManager.install after the file is read.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.install(uri)
    }

    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            viewModel.clearNotice()
        }
    }

    LaunchedEffect(showAddonDialog) {
        if (showAddonDialog) viewModel.clearDraftHealth()
    }

    if (showAddonDialog) {
        AlertDialog(
            onDismissRequest = { showAddonDialog = false },
            title = { Text(if (editingAddonId == null) "Add Streaming Addon" else "Edit Streaming Addon") },
            text = {
                Column {
                    Text(
                        "Enter the URL of your personal Addon service (e.g. http://10.0.2.2:8787/a/<token>/ or custom host):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = addonInputText,
                        onValueChange = {
                            addonInputText = it
                            viewModel.clearDraftHealth()
                        },
                        label = { Text(stringResource(R.string.mod_addon_url)) },
                        placeholder = { Text("https://example.com/a/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    draftHealth?.let { health ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            health.label(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (health is AddonHealth.Ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toSave = addonInputText.trim()
                        if (toSave.isNotBlank()) {
                            viewModel.saveAddon(editingAddonId, toSave)
                            showAddonDialog = false
                        }
                    },
                ) {
                    Text(if (editingAddonId == null) "Connect" else "Save")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { viewModel.testDraft(addonInputText) },
                        enabled = !busy && addonInputText.isNotBlank(),
                    ) {
                        Text(if (busy) "Testing…" else "Test")
                    }
                    TextButton(onClick = { showAddonDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_modules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // --- Section 1: HTTP Addons ---
            item {
                Text(
                    "Streaming Addons",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Take requests directly from personal or community Addon services. Enabled addons are tried in this order: the first one that has the track plays it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            itemsIndexed(httpAddons, key = { _, it -> it.id }) { index, addon ->
                val health = addonHealthMap[addon.id]
                Card(
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Cloud,
                                contentDescription = null,
                                tint = if (health is AddonHealth.Unreachable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${index + 1}. " + addon.name.ifBlank { "HTTP Addon" },
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                val masked = if (addon.url.length > 36) addon.url.take(24) + "..." + addon.url.takeLast(8) else addon.url
                                Text(
                                    masked,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = addon.enabled,
                                onCheckedChange = { viewModel.toggleAddon(addon.id, it) },
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        val statusText = health?.label() ?: if (addon.enabled) "Enabled" else "Disabled"
                        val statusColor = when (health) {
                            is AddonHealth.Ok -> MaterialTheme.colorScheme.primary
                            is AddonHealth.Unreachable, is AddonHealth.Rejected -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        // Status on its own line: with the HTTP code it no longer fits beside the buttons.
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (httpAddons.size > 1) {
                                    IconButton(onClick = { viewModel.moveAddon(addon.id, -1) }, enabled = index > 0) {
                                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                                    }
                                    IconButton(onClick = { viewModel.moveAddon(addon.id, 1) }, enabled = index < httpAddons.lastIndex) {
                                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                                    }
                                }
                                TextButton(
                                    onClick = { viewModel.testAddon(addon.id) },
                                    enabled = !busy,
                                ) {
                                    Text("Test")
                                }
                                IconButton(
                                    onClick = {
                                        editingAddonId = addon.id
                                        addonInputText = addon.url
                                        showAddonDialog = true
                                    },
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.mod_edit_cd))
                                }
                                IconButton(
                                    onClick = { viewModel.removeAddon(addon.id) },
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.mod_remove_cd))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    onClick = {
                        editingAddonId = null
                        addonInputText = ""
                        showAddonDialog = true
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (httpAddons.isEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (httpAddons.isEmpty()) Icons.Filled.Cloud else Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Add Streaming Addon",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (httpAddons.isEmpty()) "Connect to your personal or custom Addon server URL" else "Add another addon, tried after the ones above",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (httpAddons.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Switch to the next source after", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "How long an addon gets to find a track, or to start it, before the next one is tried",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text("$addonSwitchSeconds s", style = MaterialTheme.typography.titleMedium)
                            }
                            Slider(
                                value = addonSwitchSeconds.toFloat(),
                                onValueChange = { viewModel.setAddonSwitchSeconds(it.roundToInt()) },
                                valueRange = ADDON_SWITCH_SECONDS_RANGE.first.toFloat()..ADDON_SWITCH_SECONDS_RANGE.last.toFloat(),
                                steps = ADDON_SWITCH_SECONDS_RANGE.last - ADDON_SWITCH_SECONDS_RANGE.first - 1,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Direct source stream", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "A track plays from your addon from the first note, with one stream and no quality switch mid-song. YouTube is used only when no addon has it.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(checked = directSourceStream, onCheckedChange = viewModel::setDirectSourceStream)
                            }
                        }
                    }
                }
            }

            // --- Section 2: Local .lwp Packages ---
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_modules_query_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.settings_modules_query_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = preferModules, onCheckedChange = viewModel::setPreferModules)
                }
            }

            item {
                Card(
                    onClick = { if (!busy) picker.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_modules_add),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(R.string.settings_modules_add_sub),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (modules.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_modules_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            items(modules, key = { it.manifest.id }) { module ->
                ModuleRow(
                    module = module,
                    onToggle = { viewModel.setEnabled(module.manifest.id, it) },
                    onRemove = { viewModel.remove(module.manifest.id) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ModuleRow(
    module: InstalledProviderModule,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val manifest = module.manifest
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Extension, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(manifest.name.ifBlank { manifest.id }, fontWeight = FontWeight.Bold)
                Text(
                    "${manifest.version.ifBlank { "?" }} • ${manifest.capabilities.take(4).joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = module.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = null)
            }
        }
    }
}

private fun AddonHealth.label(): String = when (this) {
    is AddonHealth.Ok -> info ?: "Connected"
    is AddonHealth.Unreachable -> "Unreachable: $reason"
    is AddonHealth.Rejected -> "Rejected: $reason"
}
