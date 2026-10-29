package com.fabrice.pdfbox.core.update

/**
 * Quand une vérification automatique peut partir. Une vérification qui n'a pas
 * atteint GitHub ne garde pas le créneau horaire : l'appelant remet l'horodatage
 * à zéro en cas d'échec.
 */
internal object UpdateSchedule {

    const val MIN_INTERVAL_MS = 60 * 60_000L
    const val NEVER = 0L

    fun isDue(manual: Boolean, lastCheckAt: Long, now: Long): Boolean =
        manual || lastCheckAt == NEVER || now - lastCheckAt >= MIN_INTERVAL_MS
}
