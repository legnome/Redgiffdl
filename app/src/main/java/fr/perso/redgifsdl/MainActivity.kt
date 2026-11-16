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
import java.util.Locale
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
        val id = RedgifsApi.extractId(rawText)
        if (id == null) {
            showError("Lien RedGIFs invalide.\nExemple : https://www.redgifs.com/watch/xxxx")
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
        thread(name = "redgifs-download") {
            try {
                val (video, token) = RedgifsApi.resolveVideo(id)
                ui { setStatus("Téléchargement de ${video.fileName} (${if (video.isHd) "HD" else "SD"})…", null) }

                val conn = RedgifsApi.openVideoStream(video, token)
                val path = try {
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        VideoSaver.save(appContext, video.fileName, input, total) { written, t ->
                            ui { showProgress(written, t) }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                ui {
                    finishUi()
                    setStatus("✔ Vidéo enregistrée${if (video.isHd) " (HD)" else " (SD, pas de HD dispo)"} :\n$path", R.color.ok)
                    Toast.makeText(appContext, "Enregistré : $path", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                val msg = when (e) {
                    is RedgifsApi.ApiException -> e.message
                    is java.net.UnknownHostException -> "Pas de connexion Internet"
                    is java.net.SocketTimeoutException -> "Délai dépassé, réessaie"
                    else -> "${e.javaClass.simpleName} : ${e.message}"
                }
                ui {
                    finishUi()
                    showError(msg ?: "Erreur inconnue")
                }
            } finally {
                running = false
            }
        }
    }

    private fun showProgress(written: Long, total: Long) {
        if (total > 0) {
            progress.isIndeterminate = false
            progress.progress = ((written * 100) / total).toInt()
            setStatus("Téléchargement… ${mb(written)} / ${mb(total)}", null)
        } else {
            setStatus("Téléchargement… ${mb(written)}", null)
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

    private fun mb(bytes: Long) = String.format(Locale.FRANCE, "%.1f Mo", bytes / 1_048_576.0)

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
