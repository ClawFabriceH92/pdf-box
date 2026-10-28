package com.fabrice.pdfbox.feature.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fabrice.pdfbox.core.update.UpdateManager

/** Les fenêtres de la mise à jour intégrée, une par état de [UpdateManager]. */
@Composable
fun UpdateDialog(
    state: UpdateManager.State,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateManager.State.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Version ${state.version} disponible") },
            text = { Text(state.notes.ifBlank { "Une nouvelle version est prête." }) },
            confirmButton = { TextButton(onClick = onDownload) { Text("Télécharger") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Plus tard") } },
        )

        is UpdateManager.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            // Le pourcentage vit dans le TITRE : il reste lisible quelle que
            // soit la place laissée par la barre.
            title = { Text("Téléchargement… ${(state.progress * 100).toInt()} %") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "${(state.progress * 100).toInt()} % téléchargé",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
            },
            confirmButton = {},
        )

        is UpdateManager.State.ReadyToInstall -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Prêt à installer") },
            text = {
                Text(
                    "La version ${state.version} est téléchargée. Vos documents et " +
                        "annotations sont conservés par l'installation."
                )
            },
            confirmButton = { TextButton(onClick = onInstall) { Text("Installer") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Plus tard") } },
        )

        is UpdateManager.State.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("À jour") },
            text = { Text("Version installée : ${state.version}") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )

        is UpdateManager.State.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Vérification impossible") },
            text = { Text(state.detail ?: "GitHub n'a pas répondu.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            dismissButton = { TextButton(onClick = onAllow) { Text("Autoriser l'installation") } },
        )

        UpdateManager.State.Idle -> Unit
    }
}
