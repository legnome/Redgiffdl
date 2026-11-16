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
 * Organisation : les vidéos seules à la racine, une collection = un sous-dossier.
 */
object Library {

    /** Nom affiché pour la racine (vidéos téléchargées une par une). */
    const val SINGLES_LABEL = "Vidéos seules"

    private const val PART = ".part"

    class Folder(val name: String, val dir: File, val count: Int) {
        val isSingles get() = name.isEmpty()
        val label get() = if (isSingles) SINGLES_LABEL else name
    }

    fun root(context: Context): File = File(context.filesDir, "library").apply { mkdirs() }

    /** "" pour les vidéos seules, sinon le nom (nettoyé) de la collection. */
    fun folderFor(collectionName: String?): String = if (collectionName == null) "" else sanitize(collectionName)

    fun dir(context: Context, folder: String): File =
        if (folder.isEmpty()) root(context) else File(root(context), folder)

    fun label(folder: String) = folder.ifEmpty { SINGLES_LABEL }

    private fun sanitize(name: String): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .trim().trim('.').trim()
            .take(80)
        return cleaned.ifEmpty { "Collection" }
    }

    /**
     * Clé d'identification d'un fichier déjà téléchargé : l'identifiant RedGIFs en minuscules.
     * "NavyAdolescentRabbit.mp4", "NavyAdolescentRabbit (1).mp4" et
     * "NavyAdolescentRabbit-mobile.mp4" donnent tous "navyadolescentrabbit".
     */
    fun keyOf(fileName: String): String =
        fileName.lowercase()
            .substringBeforeLast('.')
            .replace(Regex("""\s*\(\d+\)$"""), "")
            .removeSuffix("-mobile")

    private fun isMedia(f: File) = f.isFile && !f.name.endsWith(PART) && !f.name.startsWith(".")

    /** Clés des fichiers déjà présents dans le dossier (voir [keyOf]). */
    fun existingKeys(context: Context, folder: String): Set<String> =
        dir(context, folder).listFiles()?.filter(::isMedia)?.mapTo(HashSet()) { keyOf(it.name) } ?: emptySet()

    /** Les dossiers non vides : "Vidéos seules" d'abord, puis les collections par ordre alphabétique. */
    fun folders(context: Context): List<Folder> {
        val root = root(context)
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

    /** Écrit d'abord dans un fichier ".part", renommé seulement si le téléchargement est complet. */
    fun save(
        context: Context,
        folder: String,
        fileName: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (written: Long, total: Long) -> Unit,
    ): File {
        val dir = dir(context, folder)
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
        Thumbs.forget(context, file)
    }

    fun deleteFolder(context: Context, folder: Folder) {
        media(folder.dir).forEach { delete(context, it) }
        if (!folder.isSingles) folder.dir.deleteRecursively()
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
                if (keyOf(name) !in existingKeys(context, folder)) {
                    resolver.openInputStream(uri)?.use { input -> save(context, folder, name, input, -1) { _, _ -> } }
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
                if (keyOf(file.name) !in existingKeys(context, folder)) {
                    file.inputStream().use { save(context, folder, file.name, it, file.length()) { _, _ -> } }
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
