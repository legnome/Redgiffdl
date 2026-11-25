package fr.perso.redgifsdl

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Cible "Télécharger (anyDL)" du menu Partager : lance le téléchargement en arrière-plan
 * (DownloadService) sans ouvrir l'appli. Activité transparente, sans interface, fermée aussitôt :
 * on reste dans l'appli d'origine.
 *
 * La cible "anyDL" classique (MainActivity) reste disponible pour ouvrir l'appli.
 */
class ShareDownloadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare()
        finishSilently()
    }

    private fun handleShare() {
        val app = applicationContext
        val text = sharedText()
        val unknown = ArrayList<String>()
        val links = if (text.isNullOrBlank()) emptyList() else Links.parseAll(text, unknown)
        if (links.isEmpty()) {
            Toast.makeText(app, "anyDL : lien non pris en charge", Toast.LENGTH_SHORT).show()
            return
        }

        val quality = Quality.fromName(getSharedPreferences("app", MODE_PRIVATE).getString("quality", null))
        // Une demande par lien : le service les met en file d'attente (un seul thread, dans l'ordre)
        try {
            links.forEach { (link, _) -> DownloadService.start(this, link, quality) }
        } catch (e: IllegalStateException) {
            // Sécurité : si Android refuse le service en arrière-plan, on ouvre l'appli comme avant
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        val n = links.size
        val sb = StringBuilder("anyDL : téléchargement lancé ($n ${if (n > 1) "liens" else "lien"})")
        if (unknown.isNotEmpty()) sb.append(" · ${unknown.size} ignoré(s)")
        // Android 13+ : on ne demande pas la permission ici (pas d'interface), on le signale seulement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            sb.append("\nOuvre anyDL une fois pour activer les notifications")
        }
        Toast.makeText(app, sb.toString(), Toast.LENGTH_LONG).show()
    }

    /** Texte partagé : EXTRA_TEXT, sinon le texte du ClipData (certaines applis ne remplissent que celui-ci). */
    private fun sharedText(): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
        val clip = intent.clipData ?: return null
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }
            .joinToString("\n").ifBlank { null }
    }

    private fun finishSilently() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private fun component(context: Context) = ComponentName(context, ShareDownloadActivity::class.java)

        /** Cible "Télécharger (anyDL)" visible dans le menu Partager ? (activée par défaut) */
        fun isEnabled(context: Context): Boolean =
            context.packageManager.getComponentEnabledSetting(component(context)) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

        fun setEnabled(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
