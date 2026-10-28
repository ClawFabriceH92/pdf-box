package com.fabrice.pdfbox.core.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.fabrice.pdfbox.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour pilotée depuis l'application, reprise de Notes de frais.
 *
 * Le téléchargement est fait ici plutôt que par DownloadManager : GitHub confie
 * l'APK à un CDN par une chaîne de redirections que DownloadManager ne suit pas
 * toujours, ce qui donnait des fichiers tronqués refusés par l'installeur
 * (« problème lors de l'analyse du package »).
 *
 * C'est la seule connexion réseau de PDF Box : les documents, eux, ne quittent
 * jamais le téléphone.
 */
object UpdateManager {

    const val TAG = "PdfBoxUpdate"

    sealed interface State {
        data object Idle : State
        data class Available(val version: String, val notes: String, val apkUrl: String) : State
        data class Downloading(val version: String, val progress: Float) : State
        data class ReadyToInstall(val version: String, val file: File) : State
        data class UpToDate(val version: String) : State
        data class Failed(val detail: String?) : State
    }

    private const val DIR = "updates"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun checkAtLaunch(context: Context?) {
        if (BuildConfig.DEBUG) return
        check(context, manual = false)
    }

    fun checkNow(context: Context? = null) = check(context, manual = true)

    @Volatile
    private var lastCheckAt = UpdateSchedule.NEVER

    private fun check(context: Context?, manual: Boolean) {
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!UpdateSchedule.isDue(manual, lastCheckAt, now)) return
        lastCheckAt = now
        job = scope.launch {
            when (val result = UpdateChecker.check()) {
                is UpdateChecker.CheckResult.Available -> {
                    val info = result.info
                    Log.i(TAG, "version ${info.version} disponible")
                    _state.value = State.Available(info.version, info.notes, info.apkUrl)
                    // Une nouvelle version ne mérite une fenêtre que si l'installer
                    // coûte un geste : sur un réseau non facturé l'APK est récupéré
                    // tout de suite, et « Installer » attend l'utilisateur.
                    if (!manual && context != null) preparerEnSilence(context, info.version, info.apkUrl)
                }

                UpdateChecker.CheckResult.UpToDate -> {
                    Log.i(TAG, "aucune version plus récente que ${BuildConfig.VERSION_NAME}")
                    _state.value = if (manual) State.UpToDate(BuildConfig.VERSION_NAME) else State.Idle
                }

                is UpdateChecker.CheckResult.Unreachable -> {
                    Log.i(TAG, UpdateChecker.logLine(result))
                    // Une vérification ratée ne doit pas garder le créneau horaire.
                    lastCheckAt = UpdateSchedule.NEVER
                    _state.value = if (manual) State.Failed(result.reason) else State.Idle
                }
            }
        }
    }

    /**
     * Télécharge la mise à jour sans qu'on le demande, mais seulement en Wi-Fi
     * ou sur un réseau non facturé : tirer des dizaines de Mo sur les données
     * mobiles sans prévenir n'est pas un service. Sinon, bouton « Télécharger ».
     */
    private suspend fun preparerEnSilence(context: Context, version: String, url: String) {
        val dejaLa = fichierValide(context, version)
        if (dejaLa != null) {
            _state.value = State.ReadyToInstall(version, dejaLa)
            return
        }
        if (!reseauNonFacture(context)) {
            Log.i(TAG, "téléchargement différé (réseau facturé) v$version")
            return
        }
        _state.value = State.Downloading(version, 0f)
        val result = runCatching { fetch(context, version, url) }
        _state.value = result.fold(
            onSuccess = { State.ReadyToInstall(version, it) },
            onFailure = {
                Log.w(TAG, "téléchargement en arrière-plan échoué", it)
                State.Available(version, "", url)
            },
        )
    }

    private fun reseauNonFacture(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val reseau = cm.activeNetwork ?: return false
        val capacites = cm.getNetworkCapabilities(reseau) ?: return false
        capacites.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capacites.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    private fun apkFile(context: Context, version: String) =
        File(File(context.filesDir, DIR), "pdf-box-v$version.apk")

    /** L'APK déjà présent pour [version], s'il en est un vrai. */
    private fun fichierValide(context: Context, version: String): File? {
        val fichier = apkFile(context, version)
        if (!fichier.exists() || fichier.length() == 0L) return null
        return if (estUnApk(fichier)) fichier else null
    }

    /** Un fichier qui ne commence pas par « PK » est une page web, pas un APK. */
    private fun estUnApk(fichier: File): Boolean {
        val entete = ByteArray(2)
        val lus = runCatching { fichier.inputStream().use { it.read(entete) } }.getOrDefault(-1)
        return lus == 2 && String(entete) == "PK"
    }

    fun download(context: Context) {
        val available = _state.value as? State.Available ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Downloading(available.version, 0f)
            val result = runCatching { fetch(context, available.version, available.apkUrl) }
            _state.value = result.fold(
                onSuccess = { State.ReadyToInstall(available.version, it) },
                onFailure = {
                    Log.w(TAG, "téléchargement échoué", it)
                    State.Failed(it.message)
                },
            )
        }
    }

    private suspend fun fetch(context: Context, version: String, url: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = apkFile(context, version)

            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "PdfBox/${BuildConfig.VERSION_NAME}")
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
                val total = conn.contentLengthLong
                var written = 0L

                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = input.read(buffer)
                        while (read != -1) {
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                _state.value = State.Downloading(
                                    version,
                                    (written.toFloat() / total).coerceIn(0f, 1f),
                                )
                            }
                            read = input.read(buffer)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (target.length() == 0L) error("Téléchargement vide")
            if (!estUnApk(target)) {
                target.delete()
                error("fichier téléchargé invalide (ce n'est pas un APK)")
            }
            target
        }

    fun install(context: Context) {
        val ready = _state.value as? State.ReadyToInstall ?: return
        AutoUpdater.install(context, ready.file)
    }

    // minSdk 26 : canRequestPackageInstalls() existe toujours.
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(context: Context) = AutoUpdater.openInstallSettings(context)

    fun dismiss() {
        _state.value = State.Idle
    }
}
