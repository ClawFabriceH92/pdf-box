package com.fabrice.pdfbox.core.update

import com.fabrice.pdfbox.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Cherche un APK plus récent dans les releases GitHub, comme Notes de frais :
 * la release flottante `latest` porte un version.json à côté de l'APK, et ce
 * manifeste est lu avant l'API, parce qu'un appel anonyme à l'API est plafonné
 * à 60 par heure et par adresse.
 *
 * Le dépôt doit rester PUBLIC : les assets d'un dépôt privé répondent 404 sans
 * jeton, et l'application n'en a pas.
 */
object UpdateChecker {

    const val REPO = "ClawFabriceH92/pdf-box"

    const val MANIFEST_URL = "https://github.com/$REPO/releases/download/latest/version.json"
    const val API_URL = "https://api.github.com/repos/$REPO/releases"

    private const val TIMEOUT_MS = 10_000

    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
        val notes: String = "",
    )

    /** Trois issues : disponible, à jour, ou « impossible de demander ». */
    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data class Unreachable(val reason: String, val answered: Boolean = false) : CheckResult
    }

    class HttpStatusException(val code: Int, reason: String) : IOException(reason)

    suspend fun check(installed: String = BuildConfig.VERSION_NAME): CheckResult =
        withContext(Dispatchers.IO) {
            val manifest = runCatching { fetch(MANIFEST_URL, github = false) }
            manifest.getOrNull()
                ?.let { parseManifest(it, installed) }
                ?.let { return@withContext it }

            val api = runCatching { fetch(API_URL, github = true) }
            api.getOrNull()
                ?.let { parseApi(it, installed) }
                ?.let { return@withContext it }

            failureVerdict(manifest, api)
        }

    internal fun logLine(result: CheckResult.Unreachable): String =
        if (result.answered) "GitHub a répondu : ${result.reason}"
        else "vérification impossible : ${result.reason}"

    internal fun failureVerdict(manifest: Result<String>, api: Result<String>): CheckResult.Unreachable {
        val failures = listOfNotNull(manifest.exceptionOrNull(), api.exceptionOrNull())
        val reseau = failures.firstOrNull { it !is HttpStatusException }
        val cause = reseau ?: api.exceptionOrNull() ?: manifest.exceptionOrNull()
        val answered = cause == null || cause is HttpStatusException
        return CheckResult.Unreachable(cause?.let { describe(it) } ?: "réponse illisible", answered)
    }

    private fun fetch(url: String, github: Boolean): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "PdfBox/${BuildConfig.VERSION_NAME}")
            if (github) conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw HttpStatusException(code, httpReason(code, conn.getHeaderField("x-ratelimit-remaining")))
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun httpReason(code: Int, rateLimitRemaining: String?): String = when {
        code == HttpURLConnection.HTTP_FORBIDDEN && rateLimitRemaining == "0" ->
            "quota GitHub épuisé pour cette adresse IP (fréquent derrière un VPN à sortie partagée)"
        code == HttpURLConnection.HTTP_FORBIDDEN -> "accès refusé par GitHub (HTTP 403)"
        code == HttpURLConnection.HTTP_NOT_FOUND -> "ressource absente (HTTP 404)"
        else -> "HTTP $code"
    }

    internal fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "nom d'hôte introuvable, DNS injoignable (${e.message.orEmpty()})"
        is SocketTimeoutException -> "délai d'attente dépassé"
        is NoRouteToHostException -> "aucune route vers l'hôte"
        is ConnectException -> "connexion impossible"
        is SSLException -> "échec TLS (${e.message.orEmpty()})"
        is IOException -> e.message.orEmpty().ifBlank { "erreur réseau" }
        else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
    }

    /**
     * L'APK porte la version dans son nom (pdf-box-v1.2.0.apk) : le manifeste
     * doit donc donner son adresse. Sans elle, il est ignoré et l'API parle.
     */
    internal fun parseManifest(body: String, installed: String): CheckResult? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val version = extractVersion(json.optString("version", "")) ?: return null
        val apkUrl = json.optString("apk", "").ifBlank { return null }
        return if (compareVersions(version, installed) > 0) {
            CheckResult.Available(UpdateInfo(version, apkUrl, json.optString("notes", "")))
        } else {
            CheckResult.UpToDate
        }
    }

    internal fun parseApi(body: String, installed: String): CheckResult? {
        val releases = runCatching { JSONArray(body) }.getOrNull() ?: return null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft")) continue
            // La release flottante est taguée "latest" : la version est dans le titre.
            val version = extractVersion(release.optString("tag_name", ""))
                ?: extractVersion(release.optString("name", ""))
                ?: continue
            val assets = release.optJSONArray("assets") ?: JSONArray()
            val apkUrl = pickApk(assets, version) ?: continue
            if (compareVersions(version, installed) > 0) {
                return CheckResult.Available(UpdateInfo(version, apkUrl, release.optString("body", "")))
            }
        }
        return CheckResult.UpToDate
    }

    fun extractVersion(text: String): String? =
        Regex("""\d+(?:\.\d+)+""").find(text)?.value

    fun pickApk(assets: JSONArray, version: String): String? {
        val apks = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name", "").endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val byVersion = apks.firstOrNull { it.optString("name").contains(version) }
        val canonical = apks.firstOrNull { it.optString("name").startsWith("pdf-box") }
        return (byVersion ?: canonical ?: apks.first()).optString("browser_download_url")
            .ifBlank { null }
    }

    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va > vb) return 1
            if (va < vb) return -1
        }
        return 0
    }
}
