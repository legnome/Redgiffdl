package fr.perso.redgifsdl

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
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
    private lateinit var extraLinks: LinearLayout
    private lateinit var qualityButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPrivacy()
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        extraLinks = findViewById(R.id.extraLinks)
        qualityButton = findViewById(R.id.qualityButton)
        updateQualityButton()
        qualityButton.setOnClickListener { chooseQuality() }
        findViewById<Button>(R.id.addLinkButton).setOnClickListener { addLinkRow() }

        // Réglage : cible "Télécharger (redDL)" dans le menu Partager (activée par défaut)
        val shareSwitch = findViewById<Switch>(R.id.shareDirectSwitch)
        shareSwitch.isChecked = ShareDownloadActivity.isEnabled(this)
        shareSwitch.setOnCheckedChangeListener { _, checked -> ShareDownloadActivity.setEnabled(this, checked) }

        findViewById<View>(R.id.root).applySystemBarInsets()

        downloadButton.setOnClickListener { startDownload(allFieldsText()) }
        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.libraryButton).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startDownload(allFieldsText()); true
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
        startDownload(text)
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Presse-papiers vide", Toast.LENGTH_SHORT).show()
        } else if (urlInput.text.isBlank()) {
            urlInput.setText(text.trim())
        } else {
            addLinkRow(text.trim()) // champ principal déjà rempli : nouveau champ
        }
    }

    /** Les versions précédentes enregistraient dans Téléchargements/RedGIFs : on rapatrie en privé. */
    private fun migrateOldDownloads() {
        val appContext = applicationContext
        migrationExecutor.execute {
            val moved = runCatching { Library.migrateFromDownloads(appContext) }.getOrDefault(0)
            if (moved > 0) DownloadState.update {
                if (!busy) {
                    status = "$moved ancien(s) téléchargement(s) déplacé(s) dans la bibliothèque privée."
                    statusColor = R.color.ok
                }
            }
        }
    }

    /** Tous les liens saisis : champ principal + champs ajoutés avec "+". */
    private fun allFieldsText(): String =
        (listOf(urlInput) + extraFields()).joinToString("\n") { it.text.toString() }

    private fun extraFields(): List<EditText> =
        (0 until extraLinks.childCount).mapNotNull { (extraLinks.getChildAt(it) as? ViewGroup)?.getChildAt(0) as? EditText }

    /** Ajoute une ligne de saisie (bouton "+"), avec un bouton ✕ pour la retirer. */
    private fun addLinkRow(text: String = "") {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val field = EditText(this).apply {
            hint = getString(R.string.hint_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
            setText(text)
        }
        val remove = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = "✕"
            contentDescription = getString(R.string.remove_link)
            minWidth = 0
            setOnClickListener { extraLinks.removeView(row) }
        }
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(remove, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        extraLinks.addView(row)
        field.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // --- Qualité ---

    private fun currentQuality(): Quality =
        Quality.fromName(getSharedPreferences("app", MODE_PRIVATE).getString("quality", null))

    private fun updateQualityButton() {
        qualityButton.text = getString(R.string.quality_button, currentQuality().label)
    }

    private fun chooseQuality() {
        val all = Quality.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.quality_title)
            .setSingleChoiceItems(all.map { it.label }.toTypedArray(), all.indexOf(currentQuality())) { dialog, which ->
                getSharedPreferences("app", MODE_PRIVATE).edit().putString("quality", all[which].name).apply()
                updateQualityButton()
                dialog.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // --- Lancement ---

    /** Découpe le texte en liens et met chacun dans la file du service de téléchargement. */
    private fun startDownload(rawText: String) {
        val unknown = ArrayList<String>()
        val links = Links.parseAll(rawText, unknown)
        if (links.isEmpty()) {
            DownloadState.update {
                status = if (rawText.isBlank()) {
                    "✖ Colle d'abord un lien."
                } else {
                    "✖ Lien non pris en charge."
                }
                statusColor = R.color.error
            }
            return
        }
        hideKeyboard()
        requestNotificationPermissionOnce()
        // Aperçu : ce qui va être téléchargé, avec des cases à décocher
        PreviewActivity.open(this, links.joinToString("\n") { it.first }, currentQuality())

        // Champs vidés : les liens sont passés à l'aperçu
        urlInput.setText("")
        extraLinks.removeAllViews()
        if (unknown.isNotEmpty()) {
            Toast.makeText(this, "${unknown.size} texte(s) non reconnu(s) ignoré(s)", Toast.LENGTH_LONG).show()
        }
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
