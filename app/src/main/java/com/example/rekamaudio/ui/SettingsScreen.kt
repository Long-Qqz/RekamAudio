package com.example.rekamaudio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.rekamaudio.BuildConfig
import com.example.rekamaudio.R
import com.example.rekamaudio.data.model.AudioQuality
import com.example.rekamaudio.data.model.Mp3Bitrate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currentQuality by viewModel.audioQuality.collectAsState()
    val currentBitrate by viewModel.mp3Bitrate.collectAsState()
    val streamingEnabled by viewModel.streamingEncoding.collectAsState()
    var showQualityDialog by remember { mutableStateOf(false) }
    var showBitrateDialog by remember { mutableStateOf(false) }

    if (showQualityDialog) {
        AudioQualityDialog(
            currentQuality = currentQuality,
            onDismiss = { showQualityDialog = false },
            onConfirm = {
                viewModel.setAudioQuality(it)
                showQualityDialog = false
            }
        )
    }

    if (showBitrateDialog) {
        Mp3BitrateDialog(
            currentBitrate = currentBitrate,
            streamingEnabled = streamingEnabled,
            onDismiss = { showBitrateDialog = false },
            onConfirm = {
                viewModel.setMp3Bitrate(it)
                showBitrateDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Text(
                text = stringResource(R.string.audio_category),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
            )

            SettingsItem(
                title = stringResource(R.string.audio_quality),
                subtitle = when (currentQuality) {
                    AudioQuality.HIGH_QUALITY_WAV -> stringResource(R.string.high_quality_wav)
                    AudioQuality.MEDIUM_QUALITY_M4A -> stringResource(R.string.medium_quality_m4a)
                    AudioQuality.COMPATIBLE_QUALITY_MP3 -> stringResource(R.string.compatible_quality_mp3)
                },
                onClick = { showQualityDialog = true }
            )

            if (currentQuality == AudioQuality.COMPATIBLE_QUALITY_MP3) {
                SettingsItem(
                    title = stringResource(R.string.mp3_bitrate),
                    subtitle = currentBitrate.label,
                    onClick = { showBitrateDialog = true }
                )

                // Streaming encoding toggle (only for MP3)
                SettingsSwitchItem(
                    title = stringResource(R.string.streaming_encoding),
                    subtitle = stringResource(
                        if (streamingEnabled) R.string.streaming_encoding_on else R.string.streaming_encoding_off
                    ),
                    checked = streamingEnabled,
                    onCheckedChange = { viewModel.setStreamingEnabled(it) }
                )
            }

            Text(
                text = stringResource(R.string.about_category),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
            )

            SettingsItem(
                title = stringResource(R.string.app_version),
                subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onClick = {}
            )
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { onCheckedChange(!checked) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun Mp3BitrateDialog(
    currentBitrate: Mp3Bitrate,
    streamingEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Mp3Bitrate) -> Unit
) {
    var selectedBitrate by remember { mutableStateOf(currentBitrate) }
    val availableBitrates = remember(streamingEnabled) {
        if (streamingEnabled) {
            Mp3Bitrate.values().filter { it.streamingSupported }
        } else {
            Mp3Bitrate.values().toList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_mp3_bitrate)) },
        text = {
            Column(Modifier.selectableGroup()) {
                availableBitrates.forEach { bitrate ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .selectable(
                                selected = (bitrate == selectedBitrate),
                                onClick = { selectedBitrate = bitrate },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (bitrate == selectedBitrate),
                            onClick = null
                        )
                        Text(
                            text = when (bitrate) {
                                Mp3Bitrate.BITRATE_128 -> stringResource(R.string.bitrate_128_desc)
                                Mp3Bitrate.BITRATE_192 -> stringResource(R.string.bitrate_192_desc)
                                Mp3Bitrate.BITRATE_320 -> stringResource(R.string.bitrate_320_desc)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedBitrate) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun AudioQualityDialog(
    currentQuality: AudioQuality,
    onDismiss: () -> Unit,
    onConfirm: (AudioQuality) -> Unit
) {
    var selectedQuality by remember { mutableStateOf(currentQuality) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_audio_quality)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AudioQuality.values().forEach { quality ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .selectable(
                                selected = (quality == selectedQuality),
                                onClick = { selectedQuality = quality },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (quality == selectedQuality),
                            onClick = null // null recommended for accessibility with selectable
                        )
                        Text(
                            text = when (quality) {
                                AudioQuality.HIGH_QUALITY_WAV -> stringResource(R.string.high_quality_desc)
                                AudioQuality.MEDIUM_QUALITY_M4A -> stringResource(R.string.medium_quality_desc)
                                AudioQuality.COMPATIBLE_QUALITY_MP3 -> stringResource(R.string.compatible_quality_desc)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedQuality) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
