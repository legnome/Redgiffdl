package fr.perso.redgifsdl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Bouton "Censure" : crée une COPIE de la vidéo (ou de l'image) avec des carrés noirs sur les
 * parties intimes détectées. L'original n'est pas modifié. Exécuté dans DownloadService
 * (arrière-plan, continue écran verrouillé). Réglages = défauts de legnome.
 *
 * Les zones détectées sont gardées dans un fichier caché ".<nom>.zones.json" (format legnome) à côté
 * de la vidéo : une nouvelle censure de la même vidéo ne refait pas l'analyse.
 */
object CensorJob {

    /**
     * Réglages de legnome, sauf la taille des rectangles : réduite d'au moins 50 % en largeur et en hauteur
     * par rapport à legnome (130 % -> 60 % de la zone détectée, toujours centrés) : 1 + 2 * padding = 0.6.
     */
    val OPTIONS = Nudity.Options(padding = -0.2)

    @Volatile
    private var model: ByteArray? = null

    private fun modelBytes(context: Context): ByteArray =
        model ?: context.assets.open(NudeDetector.ASSET).use { it.readBytes() }.also { model = it }

    fun zonesFileFor(file: File) = File(file.parentFile, ".${file.name}.zones.json")

    /** Nom de la copie : "Titre [id] - censuré.mp4". */
    private fun censoredName(file: File, ext: String) = "${file.nameWithoutExtension} - censuré.$ext"

    fun run(context: Context, file: File, listener: Downloader.Listener): Downloader.Outcome {
        if (!file.isFile) throw RedgifsApi.ApiException("Fichier introuvable")
        val (site, folder) = Library.locate(context, file) ?: throw RedgifsApi.ApiException("Fichier hors de la bibliothèque")
        val opts = OPTIONS
        listener.onStatus("Censure : chargement du modèle…")
        listener.onProgress(null)
        NudeDetector(modelBytes(context)).use { detector ->
            return if (Library.isImage(file)) {
                censorImage(context, file, site, folder, detector, opts)
            } else {
                censorVideo(context, file, site, folder, detector, opts, listener)
            }
        }
    }

    private fun censorVideo(
        context: Context,
        file: File,
        site: Site,
        folder: String,
        detector: NudeDetector,
        opts: Nudity.Options,
        listener: Downloader.Listener,
    ): Downloader.Outcome {
        val vc = VideoCensor(detector)
        val zonesFile = zonesFileFor(file)
        val zones = loadZones(zonesFile, vc.probe(file), opts) ?: run {
            vc.detect(file, opts) { p ->
                listener.onStatus("Censure de ${file.name}\nAnalyse des images… ${pct(p)}")
                listener.onProgress(p * 0.5f)
            }.also { runCatching { zonesFile.writeText(it.toJson()) } }
        }
        if (zones.frames.isEmpty()) {
            return Downloader.Outcome(
                "Censure : aucune partie intime détectée dans ${file.name}, pas de copie créée.",
                true, "Censure : rien détecté",
            )
        }
        val tmp = File(context.cacheDir, "censure-${UUID.randomUUID()}.mp4")
        try {
            vc.censor(file, zones, tmp, opts.hold ?: zones.step) { p ->
                listener.onStatus("Censure de ${file.name}\nCréation de la copie… ${pct(p)}")
                listener.onProgress(0.5f + p * 0.5f)
            }
            val out = Library.importFile(context, site, folder, censoredName(file, "mp4"), tmp)
            val seconds = zones.frames.size * zones.step / zones.fps
            return Downloader.Outcome(
                "Copie censurée créée :\n${out.name}\n" +
                    "(zones détectées sur ${zones.frames.size} image(s) analysée(s), ≈ ${Math.round(seconds)} s de vidéo)\n" +
                    "La détection n'est pas parfaite : vérifie la copie avant de la montrer.",
                true, "Censure : ${out.name}",
            )
        } finally {
            tmp.delete()
        }
    }

    /** Zones déjà calculées pour cette vidéo avec les mêmes réglages ? */
    private fun loadZones(f: File, info: VideoCensor.VideoInfo, opts: Nudity.Options): Nudity.Zones? {
        if (!f.isFile) return null
        val z = runCatching { Nudity.Zones.fromJson(f.readText()) }.getOrNull() ?: return null
        val same = z.width == info.width && z.height == info.height && z.step == opts.step &&
            z.threshold == opts.threshold && z.padding == opts.padding && z.square == opts.square &&
            z.labels.toSet() == opts.labels.toSet()
        return z.takeIf { same }
    }

    private fun censorImage(
        context: Context,
        file: File,
        site: Site,
        folder: String,
        detector: NudeDetector,
        opts: Nudity.Options,
    ): Downloader.Outcome {
        val src = BitmapFactory.decodeFile(file.path) ?: throw RedgifsApi.ApiException("Image illisible")
        try {
            val w = src.width
            val h = src.height
            // Image réduite en haut à gauche d'un carré 320x320 noir (comme NudeNet)
            val (sw, sh) = Nudity.letterboxSize(w, h)
            val square = Bitmap.createBitmap(Nudity.INPUT, Nudity.INPUT, Bitmap.Config.ARGB_8888)
            Canvas(square).apply {
                drawColor(Color.BLACK)
                drawBitmap(src, null, Rect(0, 0, sw, sh), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val px = IntArray(Nudity.INPUT * Nudity.INPUT)
            square.getPixels(px, 0, Nudity.INPUT, 0, 0, Nudity.INPUT, Nudity.INPUT)
            square.recycle()
            val boxes = Nudity.select(detector.detect(Nudity.tensorFromArgb(px), w, h), opts, w, h)
            if (boxes.isEmpty()) {
                return Downloader.Outcome(
                    "Censure : aucune partie intime détectée dans ${file.name}, pas de copie créée.",
                    true, "Censure : rien détecté",
                )
            }
            val out = src.copy(Bitmap.Config.ARGB_8888, true)
            val black = Paint().apply { color = Color.BLACK }
            Canvas(out).apply { boxes.forEach { b -> drawRect(Rect(b.x, b.y, b.x + b.w, b.y + b.h), black) } }
            val png = file.extension.lowercase() == "png"
            val ext = if (png) "png" else "jpg"
            val tmp = File(context.cacheDir, "censure-${UUID.randomUUID()}.$ext")
            try {
                FileOutputStream(tmp).use {
                    out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
                }
                out.recycle()
                val saved = Library.importFile(context, site, folder, censoredName(file, ext), tmp)
                return Downloader.Outcome(
                    "Copie censurée créée :\n${saved.name}\n(${boxes.size} zone(s) recouverte(s))",
                    true, "Censure : ${saved.name}",
                )
            } finally {
                tmp.delete()
            }
        } finally {
            src.recycle()
        }
    }

    private fun pct(p: Float) = String.format(Locale.FRANCE, "%d %%", Math.round(p * 100))
}
