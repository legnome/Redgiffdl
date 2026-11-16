package fr.perso.redgifsdl

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Actions sur un fichier de la bibliothèque : supprimer, ou déplacer dans la galerie
 * (Films/redDL[/collection] pour les vidéos, Images/redDL[/collection] pour les images),
 * ce qui le rend visible dans la Galerie et le retire de la bibliothèque privée.
 */
object GalleryExport {

    private const val ALBUM = "redDL"
    private const val REQ_STORAGE = 4242
    private val main = Handler(Looper.getMainLooper())

    // Action en attente de la permission de stockage (Android 8/9 uniquement)
    private var pending: (() -> Unit)? = null

    /** Demande confirmation puis supprime. */
    fun confirmDelete(activity: Activity, file: File, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setMessage("Supprimer définitivement ${file.name} ?")
            .setPositiveButton("Supprimer") { _, _ ->
                Library.removeByUser(activity, file)
                Toast.makeText(activity, "Supprimé", Toast.LENGTH_SHORT).show()
                onDone()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Demande confirmation puis déplace vers la galerie (en arrière-plan). */
    fun confirmMove(activity: Activity, file: File, onDone: () -> Unit) {
        val dest = destination(file)
        AlertDialog.Builder(activity)
            .setMessage(
                "Déplacer ${file.name} dans la galerie ?\n\n" +
                    "Il sera enregistré dans $dest (visible dans la Galerie et par les autres applis) " +
                    "et retiré de la bibliothèque privée."
            )
            .setPositiveButton("Déplacer") { _, _ -> withStoragePermission(activity) { move(activity, file, onDone) } }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Menu de l'appui long dans la grille. */
    fun showMenu(activity: Activity, file: File, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(file.name)
            .setItems(arrayOf("Déplacer dans la galerie", "Supprimer")) { _, which ->
                if (which == 0) confirmMove(activity, file, onDone) else confirmDelete(activity, file, onDone)
            }
            .show()
    }

    /** À appeler depuis onRequestPermissionsResult des activités. */
    fun onPermissionResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQ_STORAGE) return
        val action = pending
        pending = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            action?.invoke()
        } else {
            Toast.makeText(activity, "Permission refusée : impossible d'écrire dans la galerie", Toast.LENGTH_LONG).show()
        }
    }

    private fun withStoragePermission(activity: Activity, action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = action
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        } else {
            action()
        }
    }

    private fun move(activity: Activity, file: File, onDone: () -> Unit) {
        val app = activity.applicationContext
        Toast.makeText(activity, "Déplacement…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { copyToGallery(app, file) }
            main.post {
                result.onSuccess { path ->
                    Library.removeByUser(app, file)
                    Toast.makeText(app, "Déplacé dans la galerie : $path", Toast.LENGTH_LONG).show()
                    if (!activity.isDestroyed) onDone()
                }.onFailure { e ->
                    Toast.makeText(app, "Échec du déplacement : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun baseDir(file: File) =
        if (Library.isImage(file)) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES

    /** Sous-dossier = nom de la collection (le dossier parent dans la bibliothèque), sinon l'album seul. */
    private fun relativeDir(file: File): String {
        val parent = file.parentFile
        val inCollection = parent != null && parent.name != "library"
        return if (inCollection) "${baseDir(file)}/$ALBUM/${parent!!.name}" else "${baseDir(file)}/$ALBUM"
    }

    private fun destination(file: File) = relativeDir(file)
        .replaceFirst(Environment.DIRECTORY_MOVIES, "Films")
        .replaceFirst(Environment.DIRECTORY_PICTURES, "Images")

    private fun mimeOf(file: File) = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "video/mp4"
    }

    /** @return le chemin lisible de la copie dans la galerie. */
    private fun copyToGallery(context: Context, file: File): String {
        val relDir = relativeDir(file)
        val mime = mimeOf(file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = if (Library.isImage(file)) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("création impossible")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
                    ?: throw IOException("écriture impossible")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return destination(file) + "/" + file.name
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), relDir)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("impossible de créer ${dir.path}")
            var target = File(dir, file.name)
            var n = 1
            while (target.exists()) target = File(dir, "${file.nameWithoutExtension} (${n++}).${file.extension}")
            try {
                file.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it, 64 * 1024) } }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
            return destination(file) + "/" + target.name
        }
    }
}
