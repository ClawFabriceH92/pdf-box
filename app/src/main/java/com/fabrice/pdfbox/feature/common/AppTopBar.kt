package com.fabrice.pdfbox.feature.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fabrice.pdfbox.BuildConfig

const val APP_TITLE = "PDF Box"
const val APP_AUTHOR = "by Fabrice Heuvrard"

/**
 * Barre commune aux écrans hors lecteur. Le nom tient sur deux lignes :
 * « PDF Box by Fabrice Heuvrard » d'un seul tenant serait tronqué sur la
 * plupart des téléphones, et c'est justement la signature qui disparaîtrait.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(screen: String, onCheckUpdate: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text(
                    APP_TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1
                )
                Text(
                    "$APP_AUTHOR · $screen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        actions = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Vérifier les mises à jour") },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = { menu = false; onCheckUpdate() }
                    )
                    DropdownMenuItem(
                        text = { Text("À propos") },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                        onClick = { menu = false; about = true }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )

    if (about) {
        AboutDialog(
            onCheckUpdate = { about = false; onCheckUpdate() },
            onDismiss = { about = false }
        )
    }
}

@Composable
private fun AboutDialog(onCheckUpdate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(APP_TITLE, fontWeight = FontWeight.SemiBold)
                Text(
                    APP_AUTHOR,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                InfoRow("Version installée", BuildConfig.VERSION_NAME)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Vos documents restent sur le téléphone. La seule connexion de " +
                        "l'application sert à vérifier et télécharger ses mises à jour.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = { TextButton(onClick = onCheckUpdate) { Text("Mises à jour") } }
    )
}
