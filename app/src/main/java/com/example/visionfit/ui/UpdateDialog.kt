package com.example.visionfit.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.visionfit.model.GitHubRelease
import com.example.visionfit.util.UpdateChecker
import kotlinx.coroutines.launch

enum class UpdateDialogState {
    AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    FAILED
}

@Composable
fun UpdateDialog(
    release: GitHubRelease,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialogState by remember { mutableStateOf(UpdateDialogState.AVAILABLE) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadedFile by remember { mutableStateOf<java.io.File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            if (dialogState != UpdateDialogState.DOWNLOADING) {
                onDismiss()
            }
        },
        title = {
            Text(
                text = when (dialogState) {
                    UpdateDialogState.AVAILABLE -> "Update Available"
                    UpdateDialogState.DOWNLOADING -> "Downloading..."
                    UpdateDialogState.READY_TO_INSTALL -> "Ready to Install"
                    UpdateDialogState.FAILED -> "Update Failed"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                when (dialogState) {
                    UpdateDialogState.AVAILABLE -> {
                        Text(
                            text = "Version ${release.versionName} is now available!",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Current version: ${getAppVersion(context)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (release.body.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Release Notes:",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = release.body.trim(),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Download size: ${release.apkDownloadUrl?.let { "APK" } ?: "Unknown"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    UpdateDialogState.DOWNLOADING -> {
                        Text(
                            text = "Downloading update...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "$downloadProgress%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    UpdateDialogState.READY_TO_INSTALL -> {
                        Text(
                            text = "Download complete! Tap Install to update your app.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    UpdateDialogState.FAILED -> {
                        Text(
                            text = errorMessage ?: "Could not download the update. Please try again later.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "You can also download the APK manually from the GitHub releases page.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (dialogState) {
                UpdateDialogState.AVAILABLE -> {
                    Button(
                        onClick = {
                            dialogState = UpdateDialogState.DOWNLOADING
                            scope.launch {
                                val file = UpdateChecker.downloadApk(
                                    context = context,
                                    release = release,
                                    onProgress = { progress ->
                                        downloadProgress = progress
                                    }
                                )
                                if (file != null) {
                                    downloadedFile = file
                                    dialogState = UpdateDialogState.READY_TO_INSTALL
                                } else {
                                    dialogState = UpdateDialogState.FAILED
                                    errorMessage = "Download failed"
                                }
                            }
                        }
                    ) {
                        Text("Download & Install")
                    }
                }
                UpdateDialogState.DOWNLOADING -> {
                    // No button while downloading
                }
                UpdateDialogState.READY_TO_INSTALL -> {
                    Button(
                        onClick = {
                            downloadedFile?.let { file ->
                                if (UpdateChecker.installApk(context, file)) {
                                    onDismiss()
                                } else {
                                    dialogState = UpdateDialogState.FAILED
                                    errorMessage = "Could not launch installer"
                                }
                            }
                        }
                    ) {
                        Text("Install")
                    }
                }
                UpdateDialogState.FAILED -> {
                    Button(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            }
        },
        dismissButton = {
            when (dialogState) {
                UpdateDialogState.AVAILABLE -> {
                    TextButton(onClick = onDismiss) {
                        Text("Later")
                    }
                }
                UpdateDialogState.DOWNLOADING -> {
                    // No dismiss while downloading
                }
                UpdateDialogState.READY_TO_INSTALL -> {
                    TextButton(onClick = { onDismiss() }) {
                        Text("Later")
                    }
                }
                UpdateDialogState.FAILED -> {
                    // OK is the confirm button
                }
            }
        }
    )
}

private fun getAppVersion(context: Context): String {
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
    } catch (e: Exception) {
        "Unknown"
    }
}