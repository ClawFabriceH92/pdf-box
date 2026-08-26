package com.fabrice.pdfbox

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.fabrice.pdfbox.core.data.Library
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfBoxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // PDFBox-Android lit ses tables de polices et de codage depuis les
        // assets : sans cette initialisation, toute écriture de texte échoue.
        PDFBoxResourceLoader.init(applicationContext)
        Library.init(applicationContext)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_TASKS,
            getString(R.string.notif_channel_tasks),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_tasks_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_TASKS = "tasks"
    }
}
