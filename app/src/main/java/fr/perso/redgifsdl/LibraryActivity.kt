package fr.perso.redgifsdl

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * Bibliothèque privée.
 * - sans extra : liste des dossiers ("Vidéos seules" + une entrée par collection)
 * - avec EXTRA_FOLDER : grille de miniatures du dossier
 * Appui long = supprimer.
 */
class LibraryActivity : Activity() {

    private var folder: String? = null
    private lateinit var folderList: ListView
    private lateinit var grid: GridView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPrivacy()
        setContentView(R.layout.activity_library)
        findViewById<View>(R.id.root).applySystemBarInsets()
        folderList = findViewById(R.id.folderList)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)

        folder = intent.getStringExtra(EXTRA_FOLDER)
        title = folder?.let { Library.label(it) } ?: getString(R.string.library)
        actionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // À la racine : toutes les collections ; dans un dossier de collection : celle-ci seulement.
        val f = folder
        if (f == null || CollectionStore.forFolder(this, f) != null) {
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
                val targets = if (f == null) {
                    CollectionStore.all(this)
                } else {
                    listOfNotNull(CollectionStore.forFolder(this, f))
                }
                updateCollections(targets)
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
        targets.forEach { DownloadService.start(this, it.url) }
        Toast.makeText(this, "Mise à jour de ${targets.size} collection(s)…", Toast.LENGTH_SHORT).show()
        // Retour à l'écran principal pour suivre la progression
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun refresh() {
        val f = folder
        if (f == null) showFolders() else showMedia(f)
    }

    // --- Liste des dossiers ---

    private fun showFolders() {
        val folders = Library.folders(this)
        grid.visibility = View.GONE
        folderList.visibility = if (folders.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(R.string.library_empty)
        val linked = CollectionStore.all(this).mapTo(HashSet()) { it.folder }
        folderList.adapter = FolderAdapter(this, folders, linked)
        folderList.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, LibraryActivity::class.java).putExtra(EXTRA_FOLDER, folders[pos].name))
        }
        folderList.setOnItemLongClickListener { _, _, pos, _ ->
            val fo = folders[pos]
            val saved = if (fo.isSingles) null else CollectionStore.forFolder(this, fo.name)
            val deleteFolder = {
                confirm(
                    "Supprimer « ${fo.label} » et ses ${fo.count} fichier(s) ?" +
                        (if (saved != null) "\n\nLe lien de la collection sera aussi oublié." else "")
                ) {
                    Library.deleteFolder(this, fo)
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
            true
        }
    }

    private class FolderAdapter(
        val context: Context,
        val folders: List<Library.Folder>,
        val linked: Set<String>,
    ) : BaseAdapter() {
        override fun getCount() = folders.size
        override fun getItem(position: Int) = folders[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: View.inflate(context, R.layout.item_folder, null)
            val f = folders[position]
            v.findViewById<TextView>(R.id.title).text = f.label
            v.findViewById<TextView>(R.id.subtitle).text =
                "${f.count} fichier(s)" + if (!f.isSingles && f.name in linked) " · ⟳ lien enregistré" else ""
            val thumb = v.findViewById<ImageView>(R.id.thumb)
            Library.media(f.dir).firstOrNull()?.let { Thumbs.load(context, it, thumb) } ?: thumb.setImageDrawable(null)
            return v
        }
    }

    // --- Grille d'un dossier ---

    private fun showMedia(f: String) {
        val files = Library.media(Library.dir(this, f))
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
        const val EXTRA_FOLDER = "folder"
    }
}
