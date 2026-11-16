package fr.perso.redgifsdl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Miniatures générées localement (image extraite de la vidéo), mises en cache
 * en mémoire et dans le cache privé de l'appli (jamais dans la Galerie).
 */
object Thumbs {

    private const val SIZE = 320
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun cacheFile(context: Context, file: File): File {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val key = Integer.toHexString((file.path + file.length()).hashCode())
        return File(dir, "$key.jpg")
    }

    fun forget(context: Context, file: File) {
        memory.remove(file.path)
        cacheFile(context, file).delete()
    }

    /** Affiche la miniature de [file] dans [view] (chargement asynchrone, vues recyclées gérées). */
    fun load(context: Context, file: File, view: ImageView) {
        view.tag = file.path
        memory.get(file.path)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        val app = context.applicationContext
        executor.execute {
            val bmp = runCatching { loadOrCreate(app, file) }.getOrNull() ?: return@execute
            memory.put(file.path, bmp)
            main.post { if (view.tag == file.path) view.setImageBitmap(bmp) }
        }
    }

    private fun loadOrCreate(context: Context, file: File): Bitmap? {
        val cached = cacheFile(context, file)
        if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { return it }
        val bmp = (if (Library.isImage(file)) decodeImage(file) else frameOf(file)) ?: return null
        val scaled = scale(bmp)
        runCatching { FileOutputStream(cached).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        return scaled
    }

    private fun frameOf(file: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Recadrage carré centré, SIZE x SIZE. */
    private fun scale(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(cropped, SIZE, SIZE, true)
    }
}
