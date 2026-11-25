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
 * Actions sur un fichier de la bibliothèque : supprimer, ou copier dans la galerie
 * (Films/anyDL[/collection] pour les vidéos, Images/anyDL[/collection] pour les images),
 * ce qui le rend visible dans la Galerie et le retire de la bibliothèque privée.
 */
object GalleryExport {

    private const val ALBUM = "anyDL"
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

    /** Demande confirmation puis copie dans la galerie (en arrière-plan). L'original reste dans l'appli. */
    fun confirmCopy(activity: Activity, file: File) {
        val dest = destination(activity, file)
        AlertDialog.Builder(activity)
            .setMessage(
                "Copier ${file.name} dans la galerie ?\n\n" +
                    "Une copie sera enregistrée dans $dest (visible dans la Galerie et par les autres applis). " +
                    "La vidéo reste aussi dans la bibliothèque de l'appli."
            )
            .setPositiveButton("Copier") { _, _ -> withStoragePermission(activity) { copy(activity, file) } }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Demande confirmation puis lance la censure (copie avec carrés noirs) en arrière-plan. */
    fun confirmCensor(activity: Activity, file: File) {
        AlertDialog.Builder(activity)
            .setTitle("Censurer")
            .setMessage(
                "Créer une copie de ${file.name} avec des carrés noirs sur les parties intimes détectées ?\n\n" +
                    "L'original est conservé. L'analyse se fait en arrière-plan (compte quelques minutes " +
                    "pour une vidéo d'une minute). La détection automatique peut rater des zones."
            )
            .setPositiveButton("Censurer") { _, _ ->
                DownloadService.startCensor(activity, file)
                Toast.makeText(activity, "Censure lancée (suivi sur l'écran principal)", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Menu de l'appui long dans la grille. */
    fun showMenu(activity: Activity, file: File, onDone: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(file.name)
            .setItems(arrayOf("Copier dans la galerie", "Censurer (copie)", "Supprimer")) { _, which ->
                when (which) {
                    0 -> confirmCopy(activity, file)
                    1 -> confirmCensor(activity, file)
                    else -> confirmDelete(activity, file, onDone)
                }
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

    private fun copy(activity: Activity, file: File) {
        val app = activity.applicationContext
        Toast.makeText(activity, "Copie dans la galerie…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { copyToGallery(app, file) }
            main.post {
                result.onSuccess { path ->
                    Toast.makeText(app, "Copié dans la galerie : $path", Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    Toast.makeText(app, "Échec de la copie : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun baseDir(file: File) =
        if (Library.isImage(file)) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES

    /** Films/anyDL/<Site>[/<collection>] (Images/… pour les images). */
    private fun relativeDir(context: Context, file: File): String {
        val (site, folder) = Library.locate(context, file) ?: (null to "")
        return listOfNotNull(baseDir(file), ALBUM, site?.label, folder.ifEmpty { null }).joinToString("/")
    }

    private fun destination(context: Context, file: File) = relativeDir(context, file)
        .replaceFirst(Environment.DIRECTORY_MOVIES, "Films")
        .replaceFirst(Environment.DIRECTORY_PICTURES, "Images")

    private fun mimeOf(file: File) = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ts" -> "video/mp2t"
        "webm" -> "video/webm"
        else -> "video/mp4"
    }

    /** @return le chemin lisible de la copie dans la galerie. */
    private fun copyToGallery(context: Context, file: File): String {
        val relDir = relativeDir(context, file)
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
            return destination(context, file) + "/" + file.name
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
            return destination(context, file) + "/" + target.name
        }
    }
}
