package fr.perso.redgifsdl

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Aperçu avant téléchargement : pour chaque lien, ce qui va être téléchargé (miniature, titre, qualité),
 * avec une case cochée par défaut. On décoche ce qu'on ne veut pas, puis "Télécharger (N)".
 * Un appui sur l'en-tête d'un lien coche/décoche tout ce lien.
 */
class PreviewActivity : Activity() {

    private class Group(val link: String, val target: Links.Target) {
        var label: String = Links.describe(target)
        var items: List<Downloader.PreviewItem> = emptyList()
        var error: String? = null
        var loading = true
    }

    /** Ligne de la liste : en-tête de lien, ou élément. */
    private class Row(val group: Group, val item: Downloader.PreviewItem?)

    private val groups = ArrayList<Group>()
    private val rows = ArrayList<Row>()
    private val unchecked = HashSet<String>() // clés "lien|id" décochées (tout est coché par défaut)
    private lateinit var adapter: RowAdapter
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var confirm: Button
    private lateinit var toggleAll: Button
    private lateinit var quality: Quality
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPrivacy()
        setContentView(R.layout.activity_preview)
        findViewById<View>(R.id.root).applySystemBarInsets()
        title = getString(R.string.preview_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        status = findViewById(R.id.previewStatus)
        progress = findViewById(R.id.previewProgress)
        confirm = findViewById(R.id.confirmButton)
        toggleAll = findViewById(R.id.toggleAllButton)
        quality = Quality.fromName(intent.getStringExtra(EXTRA_QUALITY))

        val list = findViewById<ListView>(R.id.previewList)
        adapter = RowAdapter(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> onRowClick(rows[pos]) }
        confirm.setOnClickListener { startDownloads() }
        toggleAll.setOnClickListener {
            val all = selectableKeys()
            if (all.all { it !in unchecked }) unchecked.addAll(all) else unchecked.removeAll(all.toSet())
            refresh()
        }

        Links.parseAll(intent.getStringExtra(EXTRA_TEXT) ?: "").forEach { (link, target) -> groups += Group(link, target) }
        if (groups.isEmpty()) {
            Toast.makeText(this, "Lien non pris en charge", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        refresh()
        // Analyse des liens un par un, en arrière-plan
        val app = applicationContext
        groups.forEach { g ->
            worker.execute {
                val silent = object : Downloader.Listener {
                    override fun onStatus(text: String) {}
                    override fun onProgress(fraction: Float?) {}
                }
                try {
                    val p = Downloader(app, silent, quality).preview(g.target)
                    runOnUiThread {
                        g.label = p.label
                        g.items = p.items
                    }
                } catch (e: Exception) {
                    runOnUiThread { g.error = Downloader.describe(e) }
                } finally {
                    runOnUiThread {
                        g.loading = false
                        if (!isDestroyed) refresh()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun key(g: Group, id: String) = g.link + "|" + id

    /** Éléments qui seront vraiment téléchargés s'ils sont cochés (hors déjà présents). */
    private fun selectableKeys() = groups.flatMap { g -> g.items.filter { it.skipped == null }.map { key(g, it.id) } }

    private fun onRowClick(row: Row) {
        val g = row.group
        val item = row.item
        if (item == null) {
            // En-tête : coche/décoche tout le lien
            val keys = g.items.filter { it.skipped == null }.map { key(g, it.id) }
            if (keys.all { it !in unchecked }) unchecked.addAll(keys) else unchecked.removeAll(keys.toSet())
        } else if (item.skipped == null) {
            val k = key(g, item.id)
            if (!unchecked.remove(k)) unchecked.add(k)
        }
        refresh()
    }

    private fun refresh() {
        rows.clear()
        for (g in groups) {
            rows += Row(g, null)
            g.items.forEach { rows += Row(g, it) }
        }
        adapter.notifyDataSetChanged()

        val loading = groups.count { it.loading }
        val found = groups.sumOf { it.items.size }
        val selected = selectableKeys().count { it !in unchecked }
        progress.visibility = if (loading > 0) View.VISIBLE else View.GONE
        status.text = when {
            loading > 0 -> "Analyse des liens… (${groups.size - loading}/${groups.size}) — $found élément(s) trouvé(s)"
            found == 0 -> "Rien à télécharger."
            else -> "$found élément(s) trouvé(s). Décoche ce que tu ne veux pas télécharger."
        }
        confirm.isEnabled = loading == 0 && selected > 0
        confirm.text = getString(R.string.download_n, selected)
        toggleAll.setText(if (selectableKeys().all { it !in unchecked }) R.string.uncheck_all else R.string.check_all)
    }

    private fun startDownloads() {
        var links = 0
        var count = 0
        for (g in groups) {
            if (g.error != null) continue
            val wanted = g.items.filter { it.skipped == null }
            val chosen = wanted.filter { key(g, it.id) !in unchecked }
            if (chosen.isEmpty()) continue
            // Tout coché : pas de filtre (le service retélécharge la liste à jour)
            val only = if (chosen.size == wanted.size) null else chosen.map { it.id }
            DownloadService.start(this, g.link, quality, only)
            links++
            count += chosen.size
        }
        Toast.makeText(this, "$count téléchargement(s) lancé(s)", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private inner class RowAdapter(val context: Context) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position].item == null) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val g = row.group
            val item = row.item
            if (item == null) {
                val tv = (convertView as? TextView) ?: TextView(context).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(6))
                    setTypeface(typeface, Typeface.BOLD)
                }
                val n = g.items.count { it.skipped == null && key(g, it.id) !in unchecked }
                tv.text = when {
                    g.loading -> "${g.label}\nAnalyse…"
                    g.error != null -> "${g.label}\n✖ ${g.error}"
                    else -> "${g.label} — $n/${g.items.count { it.skipped == null }} sélectionné(s)"
                }
                return tv
            }
            val v = convertView ?: View.inflate(context, R.layout.item_preview, null)
            RemoteThumbs.load(item.thumbnail, v.findViewById<ImageView>(R.id.previewThumb))
            v.findViewById<TextView>(R.id.previewTitle).text = item.title
            v.findViewById<TextView>(R.id.previewInfo).text =
                listOfNotNull(item.quality, item.skipped?.let { "✔ $it (ignorée)" }).joinToString(" · ")
            val box = v.findViewById<CheckBox>(R.id.previewCheck)
            box.isEnabled = item.skipped == null
            box.isChecked = item.skipped == null && key(g, item.id) !in unchecked
            v.alpha = if (item.skipped == null) 1f else 0.5f
            return v
        }

        private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_QUALITY = "quality"

        fun open(context: Context, text: String, quality: Quality) {
            context.startActivity(
                Intent(context, PreviewActivity::class.java).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_QUALITY, quality.name)
            )
        }
    }
}
