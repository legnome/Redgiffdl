package fr.perso.redgifsdl

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Écran principal : saisie du lien et affichage de l'état.
 * Le téléchargement lui-même tourne dans [DownloadService] (continue écran verrouillé).
 */
class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPrivacy()
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        findViewById<View>(R.id.root).applySystemBarInsets()

        downloadButton.setOnClickListener { startDownload(urlInput.text.toString()) }
        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.libraryButton).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startDownload(urlInput.text.toString()); true
            } else false
        }

        DownloadState.observer = { render() }
        render()

        if (savedInstanceState == null) {
            migrateOldDownloads()
            handleIntent(intent)
        }
    }

    override fun onDestroy() {
        DownloadState.observer = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        DownloadState.observer = { render() }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Lien reçu via "Partager" (ACTION_SEND) ou "Ouvrir avec" (ACTION_VIEW). */
    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        urlInput.setText(text.trim())
        startDownload(text)
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Presse-papiers vide", Toast.LENGTH_SHORT).show()
        } else {
            urlInput.setText(text.trim())
        }
    }

    /** Les versions précédentes enregistraient dans Téléchargements/RedGIFs : on rapatrie en privé. */
    private fun migrateOldDownloads() {
        val appContext = applicationContext
        migrationExecutor.execute {
            val moved = runCatching { Library.migrateFromDownloads(appContext) }.getOrDefault(0)
            if (moved > 0) DownloadState.update {
                if (!busy) {
                    status = "$moved fichier(s) déplacé(s) de Téléchargements/RedGIFs vers la bibliothèque " +
                        "privée (ils n'apparaissent plus dans la Galerie)."
                    statusColor = R.color.ok
                }
            }
        }
    }

    private fun startDownload(rawText: String) {
        if (RedgifsApi.parse(rawText) == null) {
            DownloadState.update {
                status = "✖ Lien RedGIFs invalide.\nExemples :\nhttps://www.redgifs.com/watch/xxxx\n" +
                    "https://www.redgifs.com/users/nom/collections/xxxx"
                statusColor = R.color.error
            }
            return
        }
        hideKeyboard()
        requestNotificationPermissionOnce()
        if (DownloadState.busy) {
            Toast.makeText(this, "Ajouté à la file d'attente", Toast.LENGTH_SHORT).show()
        }
        DownloadService.start(this, rawText)
    }

    /**
     * Android 13+ : sans cette permission, le téléchargement fonctionne quand même,
     * mais la notification de progression n'est pas affichée.
     */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("notif_asked", false)) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    /** Affiche l'état courant (appelé à chaque changement). */
    private fun render() {
        val s = DownloadState
        downloadButton.text = getString(if (s.busy) R.string.download_queue else R.string.download)
        progress.visibility = if (s.busy) View.VISIBLE else View.INVISIBLE
        val f = s.fraction
        if (f == null) {
            progress.isIndeterminate = true
        } else {
            progress.isIndeterminate = false
            progress.progress = (f * progress.max).toInt()
        }
        val text = s.status ?: ""
        status.text = if (s.queued > 0) "$text\n\n(+${s.queued} lien(s) en attente)" else text
        status.setTextColor(s.statusColor?.let { getColor(it) } ?: urlInput.currentTextColor)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    companion object {
        private const val REQ_NOTIF = 7
        private val migrationExecutor = Executors.newSingleThreadExecutor()
    }
}
