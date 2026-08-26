package com.fabrice.pdfbox

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fabrice.pdfbox.core.data.Doc
import com.fabrice.pdfbox.feature.common.AppViewModel
import com.fabrice.pdfbox.feature.common.MessageBar
import com.fabrice.pdfbox.feature.common.TaskProgress
import com.fabrice.pdfbox.feature.invoice.InvoiceScreen
import com.fabrice.pdfbox.feature.library.LibraryScreen
import com.fabrice.pdfbox.feature.reader.AnnotationsScreen
import com.fabrice.pdfbox.feature.reader.ReaderScreen
import com.fabrice.pdfbox.feature.reader.ReaderViewModel
import com.fabrice.pdfbox.feature.tools.ToolsScreen
import com.fabrice.pdfbox.ui.theme.PdfBoxTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consume(intent)
        setContent {
            PdfBoxTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    /**
     * B4 — un partage entrant, une pièce jointe ouverte, un raccourci. Les URI
     * sont déposées dans un flux que l'interface consomme une fois montée :
     * l'import ne peut donc pas se perdre parce que l'activité redémarre.
     */
    private fun consume(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().filterNotNull()
            }
            ACTION_IMPORT -> emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) pendingImports.value = pendingImports.value + uris
        when (intent.action) {
            ACTION_IMPORT -> pendingTab.value = Tab.LIBRARY
            ACTION_TOOLS -> pendingTab.value = Tab.TOOLS
        }
    }

    companion object {
        const val ACTION_IMPORT = "com.fabrice.pdfbox.action.IMPORT"
        const val ACTION_TOOLS = "com.fabrice.pdfbox.action.TOOLS"

        val pendingImports = MutableStateFlow<List<Uri>>(emptyList())
        val pendingTab = MutableStateFlow<Tab?>(null)
    }
}

enum class Tab(val label: String, val icon: Int) {
    LIBRARY("Bibliothèque", R.drawable.ic_tab_library),
    READER("Lecteur", R.drawable.ic_tab_reader),
    ANNOTATIONS("Annotations", R.drawable.ic_tab_annotate),
    TOOLS("Outils", R.drawable.ic_tab_tools)
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val app: AppViewModel = viewModel()
    val reader: ReaderViewModel = viewModel()

    var tab by remember { mutableStateOf(Tab.LIBRARY) }
    var showInvoice by remember { mutableStateOf(false) }

    val docs by app.docs.collectAsState()
    val currentDoc: Doc? = app.currentDoc(docs)
    val running by app.running.collectAsState()
    val imports by MainActivity.pendingImports.collectAsState()
    val requestedTab by MainActivity.pendingTab.collectAsState()

    // Les notifications de fin de tâche sont un confort ; l'autorisation est
    // demandée une fois, et son refus ne bloque rien.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(imports) {
        if (imports.isNotEmpty()) {
            MainActivity.pendingImports.value = emptyList()
            // Une URI reçue par partage n'est pas persistable : l'importeur en
            // fera une copie plutôt que d'en garder une référence morte.
            app.importUris(context, imports, persistable = false)
            tab = Tab.READER
        }
    }

    LaunchedEffect(requestedTab) {
        requestedTab?.let {
            tab = it
            MainActivity.pendingTab.value = null
        }
    }

    Scaffold(
        bottomBar = {
            if (!(tab == Tab.READER && reader.fullscreen)) {
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry && !showInvoice,
                            onClick = { tab = entry; showInvoice = false },
                            icon = { Icon(painterResource(entry.icon), contentDescription = entry.label) },
                            label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            app.message?.let { message ->
                MessageBar(
                    message = message.text,
                    isError = message.isError,
                    onDismiss = { app.dismissMessage() }
                )
            }
            TaskProgress(running = running, onCancel = { app.cancelTask() })

            if (showInvoice) {
                InvoiceScreen(
                    app = app,
                    doc = currentDoc,
                    onOpenPdf = { showInvoice = false; tab = Tab.READER },
                    onGoToLibrary = { showInvoice = false; tab = Tab.LIBRARY }
                )
                return@Column
            }

            when (tab) {
                Tab.LIBRARY -> LibraryScreen(
                    app = app,
                    onOpenDoc = { tab = Tab.READER },
                    onOpenInvoice = { showInvoice = true }
                )
                Tab.READER -> ReaderScreen(
                    app = app,
                    reader = reader,
                    doc = currentDoc,
                    onGoToLibrary = { tab = Tab.LIBRARY }
                )
                Tab.ANNOTATIONS -> AnnotationsScreen(
                    app = app,
                    reader = reader,
                    doc = currentDoc,
                    onGoToReader = { tab = Tab.READER }
                )
                Tab.TOOLS -> ToolsScreen(
                    app = app,
                    reader = reader,
                    doc = currentDoc,
                    onOpenLibrary = { tab = Tab.LIBRARY }
                )
            }
        }
    }
}
