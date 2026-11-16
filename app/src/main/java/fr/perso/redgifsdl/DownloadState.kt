package fr.perso.redgifsdl

import android.os.Handler
import android.os.Looper

/**
 * État des téléchargements, partagé entre le service (qui télécharge) et l'écran principal
 * (qui l'affiche, et peut être fermé/rouvert à tout moment). Lu et modifié sur le thread UI uniquement.
 */
object DownloadState {
    var busy = false
    var status: String? = null
    var statusColor: Int? = null
    /** 0..1, ou null = progression indéterminée */
    var fraction: Float? = null
    /** liens en attente derrière le téléchargement en cours */
    var queued = 0

    /** Appelé après chaque modification (l'écran principal s'y abonne). */
    var observer: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    /** Modifie l'état depuis n'importe quel thread, puis prévient l'écran. */
    fun update(block: DownloadState.() -> Unit) {
        main.post {
            block()
            observer?.invoke()
        }
    }
}
