package fr.perso.redgifsdl

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Enregistre les fichiers dans Téléchargements/RedGIFs[/sous-dossier].
 * - Android 10+ : MediaStore.Downloads, aucune permission nécessaire.
 * - Android 8/9 : fichier classique (permission WRITE_EXTERNAL_STORAGE demandée par l'activité).
 */
object VideoSaver {

    const val ROOT = "RedGIFs"

    /** Dossier relatif à Téléchargements, ex. "RedGIFs" ou "RedGIFs/Ma collection". */
    fun folderFor(collectionName: String?): String =
        if (collectionName == null) ROOT else "$ROOT/${sanitize(collectionName)}"

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

    /** Clés des fichiers déjà présents dans le dossier (voir [keyOf]). */
    fun existingKeys(context: Context, folder: String): Set<String> {
        val names = HashSet<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Sans permission, MediaStore ne liste que les fichiers créés par cette appli : c'est suffisant ici.
            val relPath = "${Environment.DIRECTORY_DOWNLOADS}/$folder/"
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relPath),
                null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { names += it } }
        }
        // Complément via le système de fichiers (Android 8/9, et fichiers visibles directement)
        runCatching { legacyDir(folder).list()?.let { names += it } }
        return names.mapTo(HashSet()) { keyOf(it) }
    }

    /** @return le chemin lisible du fichier enregistré. */
    fun save(
        context: Context,
        folder: String,
        fileName: String,
        mimeType: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (written: Long, total: Long) -> Unit,
    ): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveWithMediaStore(context, folder, fileName, mimeType, input, totalBytes, onProgress)
    } else {
        saveLegacy(context, folder, fileName, mimeType, input, totalBytes, onProgress)
    }

    private fun saveWithMediaStore(
        context: Context,
        folder: String,
        fileName: String,
        mimeType: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Impossible de créer le fichier dans Téléchargements")
        try {
            resolver.openOutputStream(uri)?.use { out -> copy(input, out, totalBytes, onProgress) }
                ?: throw IOException("Impossible d'écrire dans Téléchargements")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: fileName
        return "${Environment.DIRECTORY_DOWNLOADS}/$folder/$finalName"
    }

    @Suppress("DEPRECATION")
    private fun legacyDir(folder: String) =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder)

    private fun saveLegacy(
        context: Context,
        folder: String,
        fileName: String,
        mimeType: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val dir = legacyDir(folder)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Impossible de créer ${dir.path}")
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "mp4")
        var file = File(dir, fileName)
        var n = 1
        while (file.exists()) file = File(dir, "$base (${n++}).$ext")
        try {
            FileOutputStream(file).use { out -> copy(input, out, totalBytes, onProgress) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.path), arrayOf(mimeType), null)
        return "${Environment.DIRECTORY_DOWNLOADS}/$folder/${file.name}"
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
}
