package fr.perso.redgifsdl

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
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

        current = this
        lastStatus?.let { (text, color) -> setStatus(text, color) }
        if (busy) showBusyUi()
        if (savedInstanceState == null) {
            migrateOldDownloads()
            handleIntent(intent)
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
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
        worker.execute {
            val moved = runCatching { Library.migrateFromDownloads(appContext) }.getOrDefault(0)
            if (moved > 0) ui {
                if (!busy) {
                    it.setStatus(
                        "$moved fichier(s) déplacé(s) de Téléchargements/RedGIFs vers la bibliothèque privée " +
                            "(ils n'apparaissent plus dans la Galerie).",
                        R.color.ok,
                    )
                }
            }
        }
    }

    private fun startDownload(rawText: String) {
        if (busy) {
            Toast.makeText(this, "Un téléchargement est déjà en cours", Toast.LENGTH_SHORT).show()
            return
        }
        val target = RedgifsApi.parse(rawText)
        if (target == null) {
            showError(
                "Lien RedGIFs invalide.\nExemples :\nhttps://www.redgifs.com/watch/xxxx\n" +
                    "https://www.redgifs.com/users/nom/collections/xxxx"
            )
            return
        }
        hideKeyboard()
        busy = true
        showBusyUi()
        setStatus("Connexion à RedGIFs…", null)

        val appContext = applicationContext
        val listener = object : Downloader.Listener {
            override fun onStatus(text: String) {
                main.post {
                    lastStatus = text to null
                    current?.setStatus(text, null)
                }
            }

            override fun onProgress(fraction: Float?) {
                main.post {
                    lastFraction = fraction
                    current?.setProgressFraction(fraction)
                }
            }
        }
        worker.execute {
            try {
                val outcome = Downloader(appContext, listener).run(target)
                finishDownload(if (outcome.success) "✔ ${outcome.message}" else "✖ ${outcome.message}", outcome.success)
                main.post {
                    Toast.makeText(
                        appContext,
                        if (outcome.success) "redDL : terminé" else "redDL : terminé avec des erreurs",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                finishDownload("✖ ${Downloader.describe(e)}", false)
            }
        }
    }

    private fun showBusyUi() {
        downloadButton.isEnabled = false
        progress.visibility = View.VISIBLE
        setProgressFraction(lastFraction)
    }

    private fun setProgressFraction(fraction: Float?) {
        lastFraction = fraction
        if (fraction == null) {
            progress.isIndeterminate = true
        } else {
            progress.isIndeterminate = false
            progress.progress = (fraction * progress.max).toInt()
        }
    }

    private fun finishUi() {
        lastFraction = null
        downloadButton.isEnabled = true
        progress.isIndeterminate = false
        progress.visibility = View.INVISIBLE
    }

    private fun showError(message: String) = setStatus("✖ $message", R.color.error)

    private fun setStatus(text: String, colorRes: Int?) {
        lastStatus = text to colorRes
        status.text = text
        status.setTextColor(if (colorRes != null) getColor(colorRes) else urlInput.currentTextColor)
    }

    /** Exécute [block] sur le thread UI avec l'écran actuellement affiché (survit à une réouverture). */
    private fun ui(block: (MainActivity) -> Unit) {
        main.post { current?.let(block) }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    companion object {
        /** Un seul thread : migration puis téléchargements, jamais en parallèle. */
        private val worker = Executors.newSingleThreadExecutor()

        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        /** Fin de téléchargement : état mémorisé même si aucun écran n'est ouvert à ce moment-là. */
        private fun finishDownload(message: String, success: Boolean) {
            main.post {
                busy = false
                lastFraction = null
                lastStatus = message to (if (success) R.color.ok else R.color.error)
                current?.let {
                    it.finishUi()
                    it.setStatus(message, if (success) R.color.ok else R.color.error)
                }
            }
        }

        @Volatile
        private var busy = false

        // État affiché, conservé si l'écran est recréé pendant un téléchargement (accès thread UI)
        private var current: MainActivity? = null
        private var lastStatus: Pair<String, Int?>? = null
        private var lastFraction: Float? = null
    }
}
