package fr.perso.redgifsdl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Miniatures distantes de l'aperçu (téléchargées en arrière-plan, gardées en mémoire, réduites). */
object RemoteThumbs {

    private const val MAX_SIDE = 360
    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(url: String?, view: ImageView) {
        view.tag = url
        if (url == null) {
            view.setImageDrawable(null)
            return
        }
        cache.get(url)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        executor.execute {
            val bmp = runCatching { fetch(url) }.getOrNull() ?: return@execute
            cache.put(url, bmp)
            main.post { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val bytes = Http.getBytes(url)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
