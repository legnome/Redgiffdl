package fr.perso.redgifsdl

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Bibliothèque privée, sur trois niveaux :
 * - sans extra : liste des sites (RedGIFs, XVideos, …)
 * - EXTRA_SITE = RedGIFs sans dossier : "Vidéos seules" + une entrée par collection (bouton Mettre à jour)
 * - EXTRA_SITE (+ EXTRA_FOLDER) : grille de miniatures
 * Appui long = menu (supprimer, déplacer dans la galerie, mettre à jour une collection).
 */
class LibraryActivity : Activity() {

    private var site: Site? = null
    private var folder: String? = null
    private lateinit var folderList: ListView
    private lateinit var grid: GridView
    private lateinit var empty: TextView

    /** Une ligne de liste : site ou dossier de collection. */
    private class Row(val title: String, val subtitle: String, val cover: File?, val onClick: () -> Unit, val onLongClick: (() -> Unit)?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPrivacy()
        setContentView(R.layout.activity_library)
        findViewById<View>(R.id.root).applySystemBarInsets()
        folderList = findViewById(R.id.folderList)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)

        site = Site.fromLabel(intent.getStringExtra(EXTRA_SITE))
        folder = intent.getStringExtra(EXTRA_FOLDER)
        title = when {
            site == null -> getString(R.string.library)
            folder != null && site == Site.REDGIFS -> Library.label(folder!!)
            else -> site!!.label
        }
        actionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Liste des collections RedGIFs (niveau "dossiers RedGIFs") ? */
    private val isRedgifsFolders get() = site == Site.REDGIFS && folder == null

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Mise à jour : uniquement pour RedGIFs (toutes les collections, ou celle du dossier ouvert)
        val f = folder
        val show = isRedgifsFolders || (site == Site.REDGIFS && f != null && CollectionStore.forFolder(this, f) != null)
        if (show) {
            menu.add(Menu.NONE, MENU_UPDATE, Menu.NONE, getString(R.string.update))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            MENU_UPDATE -> {
                val f = folder
                updateCollections(
                    if (f == null) CollectionStore.all(this) else listOfNotNull(CollectionStore.forFolder(this, f))
                )
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Revérifie les collections : chaque lien passe dans la file du service de téléchargement,
     * qui ne télécharge que les vidéos manquantes (ni celles déjà présentes, ni celles exclues).
     */
    private fun updateCollections(targets: List<CollectionStore.Saved>) {
        if (targets.isEmpty()) {
            Toast.makeText(
                this,
                "Aucune collection enregistrée. Télécharge une collection par son lien pour l'ajouter.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val quality = Quality.fromName(getSharedPreferences("app", MODE_PRIVATE).getString("quality", null))
        targets.forEach { DownloadService.start(this, it.url, quality) }
        Toast.makeText(this, "Mise à jour de ${targets.size} collection(s)…", Toast.LENGTH_SHORT).show()
        // Retour à l'écran principal pour suivre la progression
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun refresh() {
        val s = site
        when {
            s == null -> showSites()
            isRedgifsFolders -> showRedgifsFolders()
            else -> showMedia(s, folder ?: "")
        }
    }

    // --- Niveau 1 : sites ---

    private fun showSites() {
        val sites = Library.sites(this)
        showRows(sites.map { e ->
            Row(e.site.label, "${e.count} fichier(s)", e.cover, onClick = {
                startActivity(Intent(this, LibraryActivity::class.java).putExtra(EXTRA_SITE, e.site.label))
            }, onLongClick = null)
        })
    }

    // --- Niveau 2 (RedGIFs) : vidéos seules + collections ---

    private fun showRedgifsFolders() {
        val folders = Library.folders(this, Site.REDGIFS)
        val linked = CollectionStore.all(this).mapTo(HashSet()) { it.folder }
        showRows(folders.map { fo ->
            val saved = !fo.isSingles && fo.name in linked
            Row(
                fo.label,
                "${fo.count} fichier(s)" + if (saved) " · ⟳ lien enregistré" else "",
                Library.media(fo.dir).firstOrNull(),
                onClick = {
                    startActivity(
                        Intent(this, LibraryActivity::class.java)
                            .putExtra(EXTRA_SITE, Site.REDGIFS.label)
                            .putExtra(EXTRA_FOLDER, fo.name)
                    )
                },
                onLongClick = { folderMenu(fo) },
            )
        })
    }

    private fun folderMenu(fo: Library.Folder) {
        val saved = if (fo.isSingles) null else CollectionStore.forFolder(this, fo.name)
        val deleteFolder = {
            confirm(
                "Supprimer « ${fo.label} » et ses ${fo.count} fichier(s) ?" +
                    (if (saved != null) "\n\nLe lien de la collection sera aussi oublié." else "")
            ) {
                Library.deleteFolder(this, Site.REDGIFS, fo)
                refresh()
            }
        }
        if (saved == null) {
            deleteFolder()
        } else {
            AlertDialog.Builder(this)
                .setTitle(fo.label)
                .setItems(arrayOf("Mettre à jour cette collection", "Supprimer le dossier")) { _, which ->
                    if (which == 0) updateCollections(listOf(saved)) else deleteFolder()
                }
                .show()
        }
    }

    private fun showRows(rows: List<Row>) {
        grid.visibility = View.GONE
        folderList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(R.string.library_empty)
        folderList.adapter = RowAdapter(this, rows)
        folderList.setOnItemClickListener { _, _, pos, _ -> rows[pos].onClick() }
        folderList.setOnItemLongClickListener { _, _, pos, _ ->
            rows[pos].onLongClick?.invoke()
            rows[pos].onLongClick != null
        }
    }

    private class RowAdapter(val context: Context, val rows: List<Row>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: View.inflate(context, R.layout.item_folder, null)
            val r = rows[position]
            v.findViewById<TextView>(R.id.title).text = r.title
            v.findViewById<TextView>(R.id.subtitle).text = r.subtitle
            val thumb = v.findViewById<ImageView>(R.id.thumb)
            r.cover?.let { Thumbs.load(context, it, thumb) } ?: run { thumb.tag = null; thumb.setImageDrawable(null) }
            return v
        }
    }

    // --- Niveau 3 : grille ---

    private fun showMedia(s: Site, f: String) {
        val files = Library.media(Library.dir(this, s, f))
        if (files.isEmpty()) {
            finish() // dossier vidé
            return
        }
        folderList.visibility = View.GONE
        empty.visibility = View.GONE
        grid.visibility = View.VISIBLE
        grid.adapter = MediaAdapter(this, files)
        grid.setOnItemClickListener { _, _, pos, _ ->
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(EXTRA_SITE, s.label)
                    .putExtra(EXTRA_FOLDER, f)
                    .putExtra(PlayerActivity.EXTRA_INDEX, pos)
            )
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            GalleryExport.showMenu(this, files[pos]) { refresh() }
            true
        }
    }

    private class MediaAdapter(val context: Context, val files: List<File>) : BaseAdapter() {
        override fun getCount() = files.size
        override fun getItem(position: Int) = files[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: View.inflate(context, R.layout.item_media, null)
            Thumbs.load(context, files[position], v.findViewById(R.id.thumb))
            v.findViewById<View>(R.id.imageBadge).visibility =
                if (Library.isImage(files[position])) View.VISIBLE else View.GONE
            return v
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        GalleryExport.onPermissionResult(this, requestCode, grantResults)
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Supprimer") { _, _ -> action() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    companion object {
        private const val MENU_UPDATE = 1
        const val EXTRA_SITE = "site"
        const val EXTRA_FOLDER = "folder"
    }
}
