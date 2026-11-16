package fr.perso.redgifsdl

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    @Volatile
    private var running = false
    private var pendingAfterPermission: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        applySystemBarInsets(findViewById(R.id.root))

        downloadButton.setOnClickListener { startDownload(urlInput.text.toString()) }
        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startDownload(urlInput.text.toString()); true
            } else false
        }

        if (savedInstanceState == null) handleIntent(intent)
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

    private fun startDownload(rawText: String) {
        if (running) {
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
        if (needsLegacyPermission()) {
            pendingAfterPermission = rawText
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        hideKeyboard()
        running = true
        downloadButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        setStatus("Connexion à RedGIFs…", null)

        val appContext = applicationContext
        val listener = object : Downloader.Listener {
            override fun onStatus(text: String) = ui { setStatus(text, null) }
            override fun onProgress(fraction: Float?) = ui {
                if (fraction == null) {
                    progress.isIndeterminate = true
                } else {
                    progress.isIndeterminate = false
                    progress.progress = (fraction * progress.max).toInt()
                }
            }
        }
        thread(name = "redgifs-download") {
            try {
                val outcome = Downloader(appContext, listener).run(target)
                ui {
                    finishUi()
                    if (outcome.success) {
                        setStatus("✔ ${outcome.message}", R.color.ok)
                    } else {
                        showError(outcome.message)
                    }
                    Toast.makeText(
                        appContext,
                        if (outcome.success) "RedGIFs DL : terminé" else "RedGIFs DL : terminé avec des erreurs",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                ui {
                    finishUi()
                    showError(Downloader.describe(e))
                }
            } finally {
                running = false
            }
        }
    }

    private fun finishUi() {
        downloadButton.isEnabled = true
        progress.isIndeterminate = false
        progress.visibility = View.INVISIBLE
    }

    private fun showError(message: String) = setStatus("✖ $message", R.color.error)

    private fun setStatus(text: String, colorRes: Int?) {
        status.text = text
        if (colorRes != null) {
            status.setTextColor(getColor(colorRes))
        } else {
            status.setTextColor(urlInput.currentTextColor)
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    // --- Android 8/9 uniquement : permission d'écriture ---

    private fun needsLegacyPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        val pending = pendingAfterPermission
        pendingAfterPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && pending != null) {
            startDownload(pending)
        } else {
            showError("Permission de stockage refusée : impossible d'enregistrer la vidéo")
        }
    }

    // --- Bord à bord (imposé à partir d'Android 15) : éviter que la barre d'état masque le contenu ---

    private fun applySystemBarInsets(root: View) {
        val base = root.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(base + b.left, base + b.top, base + b.right, base + b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    base + insets.systemWindowInsetLeft, base + insets.systemWindowInsetTop,
                    base + insets.systemWindowInsetRight, base + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    companion object {
        private const val REQ_STORAGE = 1
    }
}
