package fr.perso.redgifsdl

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Bibliothèque privée : les fichiers sont dans le stockage interne de l'appli
 * (/data/data/<appli>/files/library), inaccessible aux autres applis :
 * invisibles dans la Galerie, Google Photos et les gestionnaires de fichiers.
 * Attention : ce dossier est effacé si l'appli est désinstallée.
 *
 * Organisation : library/<Site>/… ; pour RedGIFs, les vidéos seules à la racine du site
 * et une collection = un sous-dossier. Pour les autres sites, tout à la racine du site.
 */
object Library {

    /** Nom affiché pour la racine RedGIFs (vidéos téléchargées une par une). */
    const val SINGLES_LABEL = "Vidéos seules"

    private const val PART = ".part"
    private const val LAYOUT_MARKER = ".layout2"

    class Folder(val name: String, val dir: File, val count: Int) {
        val isSingles get() = name.isEmpty()
        val label get() = if (isSingles) SINGLES_LABEL else name
    }

    class SiteEntry(val site: Site, val count: Int, val cover: File?)

    @Volatile
    private var layoutChecked = false

    fun root(context: Context): File {
        val r = File(context.filesDir, "library")
        if (!layoutChecked) {
            synchronized(this) {
                if (!layoutChecked) {
                    migrateLayout(context, r)
                    layoutChecked = true
                }
            }
        }
        return r.apply { mkdirs() }
    }

    /**
     * Versions ≤ 2.3 : tout était directement dans library/ (RedGIFs uniquement).
     * On déplace l'ensemble dans library/RedGIFs par simple renommage de dossier.
     */
    private fun migrateLayout(context: Context, r: File) {
        if (File(r, LAYOUT_MARKER).exists()) return
        val hasOld = r.isDirectory && (r.list()?.isNotEmpty() == true)
        if (hasOld) {
            val tmp = File(context.filesDir, "library_old")
            if (r.renameTo(tmp)) {
                r.mkdirs()
                if (!tmp.renameTo(File(r, Site.REDGIFS.label))) {
                    // Échec improbable : on remet en place pour ne rien perdre
                    r.delete()
                    tmp.renameTo(r)
                    return
                }
            } else {
                return
            }
        }
        r.mkdirs()
        File(r, LAYOUT_MARKER).createNewFile()
    }

    fun siteDir(context: Context, site: Site): File = File(root(context), site.label)

    /** [folder] : "" pour la racine du site, sinon le sous-dossier (collection RedGIFs). */
    fun dir(context: Context, site: Site, folder: String): File =
        if (folder.isEmpty()) siteDir(context, site) else File(siteDir(context, site), folder)

    /** "" pour les vidéos seules, sinon le nom (nettoyé) de la collection. */
    fun folderFor(collectionName: String?): String = if (collectionName == null) "" else sanitize(collectionName)

    fun label(folder: String) = folder.ifEmpty { SINGLES_LABEL }

    fun sanitize(name: String, max: Int = 80): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim().trim('.').trim()
            .take(max).trim()
        return cleaned.ifEmpty { "Collection" }
    }

    /** Nom de fichier lisible avec l'identifiant entre crochets : "Titre [id].mp4" (ou "id.mp4" sans titre). */
    fun fileNameFor(title: String?, id: String, ext: String): String {
        val t = title?.let { sanitize(it, 60) }?.takeIf { it != "Collection" }
        return if (t == null) "$id.$ext" else "$t [$id].$ext"
    }

    private val BRACKET_ID = Regex("""\[([^\[\]]+)]\s*(?:\(\d+\))?\.[A-Za-z0-9]+$""")

    /**
     * Clé d'identification d'un fichier déjà téléchargé.
     * "Titre [abc123].mp4" -> "abc123" ; sinon (RedGIFs) l'identifiant en minuscules :
     * "NavyAdolescentRabbit.mp4", "NavyAdolescentRabbit (1).mp4" et
     * "NavyAdolescentRabbit-mobile.mp4" donnent tous "navyadolescentrabbit".
     */
    fun keyOf(fileName: String): String {
        BRACKET_ID.find(fileName)?.let { return it.groupValues[1].lowercase() }
        return fileName.lowercase()
            .substringBeforeLast('.')
            .replace(Regex("""\s*\(\d+\)$"""), "")
            .removeSuffix("-mobile")
    }

    private fun isMedia(f: File) = f.isFile && !f.name.endsWith(PART) && !f.name.startsWith(".")

    /** Clés des fichiers déjà présents dans le dossier (voir [keyOf]). */
    fun existingKeys(context: Context, site: Site, folder: String): Set<String> =
        dir(context, site, folder).listFiles()?.filter(::isMedia)?.mapTo(HashSet()) { keyOf(it.name) } ?: emptySet()

    /** Sites ayant au moins un fichier, dans l'ordre de l'énumération. */
    fun sites(context: Context): List<SiteEntry> = Site.entries.mapNotNull { site ->
        val all = allMedia(siteDir(context, site))
        if (all.isEmpty()) null else SiteEntry(site, all.size, all.first())
    }

    /** Tous les fichiers d'un dossier et de ses sous-dossiers, du plus récent au plus ancien. */
    fun allMedia(dir: File): List<File> =
        dir.walkTopDown().filter(::isMedia).sortedByDescending { it.lastModified() }.toList()

    /** Dossiers non vides d'un site : "Vidéos seules" d'abord, puis les collections par ordre alphabétique. */
    fun folders(context: Context, site: Site): List<Folder> {
        val root = siteDir(context, site)
        val result = ArrayList<Folder>()
        val singles = media(root).size
        if (singles > 0) result += Folder("", root, singles)
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { d ->
            val n = media(d).size
            if (n > 0) result += Folder(d.name, d, n)
        }
        return result
    }

    /** Fichiers d'un dossier, du plus récent au plus ancien. */
    fun media(dir: File): List<File> =
        dir.listFiles()?.filter(::isMedia)?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun isImage(f: File) = f.extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp")

    /** Site et dossier d'un fichier de la bibliothèque. */
    fun locate(context: Context, file: File): Pair<Site, String>? {
        val rel = runCatching { file.relativeTo(root(context)).path }.getOrNull() ?: return null
        val parts = rel.split(File.separatorChar)
        val site = Site.fromLabel(parts.firstOrNull()) ?: return null
        return site to (if (parts.size >= 3) parts[1] else "")
    }

    /** Écrit d'abord dans un fichier ".part", renommé seulement si le téléchargement est complet. */
    fun save(
        context: Context,
        site: Site,
        folder: String,
        fileName: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (written: Long, total: Long) -> Unit,
    ): File {
        val dir = dir(context, site, folder)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Impossible de créer le dossier ${label(folder)}")
        val target = uniqueFile(dir, fileName)
        val part = File(dir, target.name + PART)
        try {
            FileOutputStream(part).use { out -> copy(input, out, totalBytes, onProgress) }
            if (!part.renameTo(target)) throw IOException("Impossible de finaliser ${target.name}")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        return target
    }

    /** Range un fichier déjà complet (ex. vidéo réassemblée dans le cache) dans la bibliothèque. */
    fun importFile(context: Context, site: Site, folder: String, fileName: String, src: File): File {
        val dir = dir(context, site, folder)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Impossible de créer le dossier ${label(folder)}")
        val target = uniqueFile(dir, fileName)
        if (!src.renameTo(target)) {
            val part = File(dir, target.name + PART)
            try {
                src.inputStream().use { input -> FileOutputStream(part).use { input.copyTo(it, 64 * 1024) } }
                if (!part.renameTo(target)) throw IOException("Impossible de finaliser ${target.name}")
            } catch (e: Exception) {
                part.delete()
                throw e
            }
            src.delete()
        }
        return target
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "mp4")
        var file = File(dir, fileName)
        var n = 1
        while (file.exists()) file = File(dir, "$base (${n++}).$ext")
        return file
    }

    fun delete(context: Context, file: File) {
        file.delete()
        File(file.parentFile, ".${file.name}.zones.json").delete() // zones de censure (CensorJob)
        Thumbs.forget(context, file)
    }

    /**
     * Suppression / déplacement demandé par l'utilisateur : si le fichier vient d'une collection RedGIFs,
     * il est noté comme exclu pour que la mise à jour ne le retélécharge pas.
     */
    fun removeByUser(context: Context, file: File) {
        val location = locate(context, file)
        delete(context, file)
        if (location != null && location.first == Site.REDGIFS && location.second.isNotEmpty()) {
            CollectionStore.exclude(context, location.second, keyOf(file.name))
        }
    }

    /** Supprime le dossier ; pour une collection RedGIFs, oublie aussi son lien. */
    fun deleteFolder(context: Context, site: Site, folder: Folder) {
        media(folder.dir).forEach { delete(context, it) }
        if (!folder.isSingles) {
            folder.dir.deleteRecursively()
            if (site == Site.REDGIFS) CollectionStore.forgetFolder(context, folder.name)
        }
    }

    private fun copy(input: InputStream, out: OutputStream, total: Long, onProgress: (Long, Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var written = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            written += n
            if (written - lastReport >= 128 * 1024) {
                lastReport = written
                onProgress(written, total)
            }
        }
        if (total > 0 && written < total) throw IOException("Téléchargement interrompu ($written / $total octets)")
        onProgress(written, total)
    }

    // --- Migration des anciennes versions (Téléchargements/RedGIFs, public) vers la bibliothèque privée ---

    private const val OLD_ROOT = "RedGIFs"

    /**
     * Déplace les fichiers de Téléchargements/RedGIFs (créés par les versions précédentes)
     * dans la bibliothèque privée, puis supprime les originaux. @return le nombre de fichiers déplacés.
     */
    fun migrateFromDownloads(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) migrateMediaStore(context) else migrateLegacy(context)

    /** "Download/RedGIFs/" -> "", "Download/RedGIFs/Ma collection/" -> "Ma collection" */
    private fun folderFromOldPath(relativePath: String): String =
        relativePath.trimEnd('/').substringAfter("${Environment.DIRECTORY_DOWNLOADS}/$OLD_ROOT", "")
            .trim('/').substringBefore('/')

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private fun migrateMediaStore(context: Context): Int {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val items = ArrayList<Triple<Long, String, String>>() // id, nom, dossier
        // Sans permission, MediaStore ne renvoie que les fichiers créés par cette appli.
        resolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$OLD_ROOT/%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                items += Triple(c.getLong(0), name, folderFromOldPath(c.getString(2) ?: ""))
            }
        }
        var moved = 0
        for ((id, name, folder) in items) {
            val uri = ContentUris.withAppendedId(collection, id)
            try {
                if (keyOf(name) !in existingKeys(context, Site.REDGIFS, folder)) {
                    resolver.openInputStream(uri)?.use { input -> save(context, Site.REDGIFS, folder, name, input, -1) { _, _ -> } }
                        ?: continue
                }
                resolver.delete(uri, null, null)
                moved++
            } catch (_: Exception) {
                // on laisse l'original en place, nouvel essai au prochain lancement
            }
        }
        return moved
    }

    @Suppress("DEPRECATION")
    private fun migrateLegacy(context: Context): Int {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return 0
        }
        val oldRoot = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), OLD_ROOT)
        if (!oldRoot.isDirectory) return 0
        var moved = 0
        val sources = (oldRoot.listFiles()?.toList() ?: emptyList()).flatMap { f ->
            if (f.isDirectory) (f.listFiles()?.map { f.name to it } ?: emptyList()) else listOf("" to f)
        }
        for ((folder, file) in sources) {
            if (!file.isFile) continue
            try {
                if (keyOf(file.name) !in existingKeys(context, Site.REDGIFS, folder)) {
                    file.inputStream().use { save(context, Site.REDGIFS, folder, file.name, it, file.length()) { _, _ -> } }
                }
                file.delete()
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
                moved++
            } catch (_: Exception) {
            }
        }
        oldRoot.listFiles()?.filter { it.isDirectory }?.forEach { it.delete() } // seulement si vides
        oldRoot.delete()
        return moved
    }
}
