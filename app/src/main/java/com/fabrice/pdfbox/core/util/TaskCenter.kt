package com.fabrice.pdfbox.core.util

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fabrice.pdfbox.PdfBoxApp
import com.fabrice.pdfbox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Traitements longs : OCR d'un document entier, compression, traitement en lot.
 *
 * Le cahier des charges prévoyait `WorkManager`. Son apport réel — survivre à la
 * mort du processus — ne se justifie pas ici : ces tâches durent des dizaines de
 * secondes, pas des heures, et relancer un OCR coûte moins cher que la
 * mécanique de reprise. Ce qui compte pour l'utilisateur, c'est qu'une tâche
 * continue quand il change d'écran ou quitte l'application, et qu'elle
 * l'avertisse en finissant : c'est exactement ce que fait ce scope applicatif.
 */
object TaskCenter {

    data class Running(
        val id: Long,
        val label: String,
        val done: Int,
        val total: Int,
        val step: String
    ) {
        val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
        val indeterminate: Boolean get() = total <= 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    private var currentJob: Job? = null
    private var nextId = 1L

    val isBusy: Boolean get() = _running.value != null

    /**
     * Lance une tâche. Une seule à la fois : deux OCR simultanés se
     * disputeraient la mémoire et finiraient tous les deux plus lentement.
     */
    fun <T> launch(
        context: Context,
        label: String,
        notifyOnFinish: Boolean = true,
        work: suspend (ProgressSink) -> T,
        onResult: suspend (TaskResult<T>) -> Unit
    ): Boolean {
        if (isBusy) return false
        val appContext = context.applicationContext
        val id = nextId++
        _running.value = Running(id, label, 0, 0, "démarrage")
        currentJob = scope.launch {
            val sink = ProgressSink { done, total, step ->
                val current = _running.value
                if (current != null && current.id == id) {
                    _running.value = current.copy(done = done, total = total, step = step)
                }
            }
            val result = runTask(label) { work(sink) }
            _running.value = null
            currentJob = null
            if (notifyOnFinish) notify(appContext, label, result)
            withContext(Dispatchers.Main) { onResult(result) }
        }
        return true
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _running.value = null
    }

    private fun notify(context: Context, label: String, result: TaskResult<*>) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) return

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pending = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val (title, text) = when (result) {
            is TaskResult.Ok -> "$label — terminé" to "Le résultat vous attend dans PDF Box."
            is TaskResult.Failed -> "$label — échec" to result.message
        }

        val notification: Notification = NotificationCompat.Builder(context, PdfBoxApp.CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(label.hashCode(), notification)
        }
    }
}
