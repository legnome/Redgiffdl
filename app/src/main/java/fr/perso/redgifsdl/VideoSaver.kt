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
 * Enregistre un flux dans Téléchargements/RedGIFs.
 * - Android 10+ : MediaStore.Downloads, aucune permission nécessaire.
 * - Android 8/9 : fichier classique (permission WRITE_EXTERNAL_STORAGE demandée par l'activité).
 */
object VideoSaver {

    const val SUBFOLDER = "RedGIFs"

    /** @return le chemin lisible du fichier enregistré. */
    fun save(
        context: Context,
        fileName: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (written: Long, total: Long) -> Unit,
    ): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveWithMediaStore(context, fileName, input, totalBytes, onProgress)
    } else {
        saveLegacy(context, fileName, input, totalBytes, onProgress)
    }

    private fun saveWithMediaStore(
        context: Context,
        fileName: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
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
        // MediaStore peut renommer le fichier s'il existe déjà, ex. "Xxx (1).mp4"
        val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: fileName
        return "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER/$finalName"
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        fileName: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBFOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Impossible de créer ${dir.path}")
        val base = fileName.substringBeforeLast('.')
        var file = File(dir, fileName)
        var n = 1
        while (file.exists()) file = File(dir, "$base (${n++}).mp4")
        try {
            FileOutputStream(file).use { out -> copy(input, out, totalBytes, onProgress) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.path), arrayOf("video/mp4"), null)
        return "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER/${file.name}"
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
