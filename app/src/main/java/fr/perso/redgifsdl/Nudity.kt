package fr.perso.redgifsdl

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Portage Kotlin de legnome (github.com/legnome/legnome) et de NudeNet 3.4 :
 * pré/post-traitement du modèle, zones agrandies, maintien des zones entre images, JSON des zones.
 * Code sans Android : testé sur PC contre l'implémentation Python d'origine.
 */
object Nudity {

    /** Toutes les classes du modèle NudeNet, dans l'ordre de ses sorties. */
    val ALL_LABELS = listOf(
        "FEMALE_GENITALIA_COVERED",
        "FACE_FEMALE",
        "BUTTOCKS_EXPOSED",
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_BREAST_EXPOSED",
        "ANUS_EXPOSED",
        "FEET_EXPOSED",
        "BELLY_COVERED",
        "FEET_COVERED",
        "ARMPITS_COVERED",
        "ARMPITS_EXPOSED",
        "FACE_MALE",
        "BELLY_EXPOSED",
        "MALE_GENITALIA_EXPOSED",
        "ANUS_COVERED",
        "FEMALE_BREAST_COVERED",
        "BUTTOCKS_COVERED",
    )

    /** Parties intimes découvertes : ce que legnome censure par défaut. */
    val DEFAULT_LABELS = listOf(
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_GENITALIA_EXPOSED",
        "ANUS_EXPOSED",
        "FEMALE_BREAST_EXPOSED",
        "BUTTOCKS_EXPOSED",
    )

    /** Résolution d'entrée du modèle 320n. */
    const val INPUT = 320

    /** Réglages par défaut de legnome (detect / censor). */
    data class Options(
        val step: Int = 5,
        val threshold: Float = 0.4f,
        val labels: List<String> = DEFAULT_LABELS,
        /**
         * Marge relative de chaque côté (legnome : 0.15 -> zone = 130 % de la détection).
         * Négative = rectangle plus petit que la détection, toujours centré dessus.
         */
        val padding: Double = 0.15,
        val square: Boolean = false,
        /** null = step (comme legnome) */
        val hold: Int? = null,
    )

    /** Détection brute du modèle (comme NudeDetector.detect). */
    data class Detection(val label: String, val score: Float, val x: Int, val y: Int, val w: Int, val h: Int)

    /** Zone retenue (agrandie), en pixels de l'image d'origine. */
    data class Box(val label: String, val score: Double, val x: Int, val y: Int, val w: Int, val h: Int)

    // ------------------------------------------------------------------ pré-traitement

    /**
     * Image 320x320 RGBA (avec bandes noires à droite/en bas, comme NudeNet) -> tenseur NCHW [1,3,320,320].
     * Ordre des canaux B, V, R : dans NudeNet, cvtColor(RGBA2BGR) puis blobFromImage(swapRB=True)
     * inversent deux fois rouge et bleu sur les images BGR d'OpenCV ; le modèle reçoit donc du BGR.
     * @param bottomUp true si les lignes sont stockées de bas en haut (lecture OpenGL).
     */
    fun tensorFromRgba(rgba: ByteArray, size: Int = INPUT, bottomUp: Boolean = false): FloatArray {
        val plane = size * size
        val t = FloatArray(3 * plane)
        for (row in 0 until size) {
            val src = (if (bottomUp) size - 1 - row else row) * size * 4
            val dst = row * size
            for (col in 0 until size) {
                val p = src + col * 4
                val r = (rgba[p].toInt() and 0xFF) / 255f
                val g = (rgba[p + 1].toInt() and 0xFF) / 255f
                val b = (rgba[p + 2].toInt() and 0xFF) / 255f
                t[dst + col] = b
                t[plane + dst + col] = g
                t[2 * plane + dst + col] = r
            }
        }
        return t
    }

    /** Même chose depuis des pixels ARGB (Bitmap.getPixels, lignes de haut en bas). */
    fun tensorFromArgb(argb: IntArray, size: Int = INPUT): FloatArray {
        val plane = size * size
        val t = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = argb[i]
            t[i] = (c and 0xFF) / 255f
            t[plane + i] = ((c shr 8) and 0xFF) / 255f
            t[2 * plane + i] = ((c shr 16) and 0xFF) / 255f
        }
        return t
    }

    /** Taille de l'image une fois réduite dans le carré du modèle (le reste = bandes noires). */
    fun letterboxSize(width: Int, height: Int, size: Int = INPUT): Pair<Int, Int> {
        val m = max(width, height).toDouble()
        return Math.round(width * size / m).toInt().coerceIn(1, size) to Math.round(height * size / m).toInt().coerceIn(1, size)
    }

    // ------------------------------------------------------------------ post-traitement (NudeNet _postprocess)

    /**
     * @param out sortie du modèle [1, 4+18, N] (N = 2100 pour 320n), à plat.
     * Calculs en float32 comme NumPy, NMS identique à cv2.dnn.NMSBoxes(0.25, 0.45).
     */
    fun postprocess(out: FloatArray, imageWidth: Int, imageHeight: Int, size: Int = INPUT): List<Detection> {
        val channels = 4 + ALL_LABELS.size
        val n = out.size / channels
        val maxSize = max(imageWidth, imageHeight)
        val xPad = maxSize - imageWidth
        val yPad = maxSize - imageHeight
        val sx = ((imageWidth + xPad).toDouble() / size).toFloat()
        val sy = ((imageHeight + yPad).toDouble() / size).toFloat()

        val boxes = ArrayList<FloatArray>()
        val scores = ArrayList<Float>()
        val classes = ArrayList<Int>()
        for (i in 0 until n) {
            var best = 4
            for (k in 5 until channels) if (out[k * n + i] > out[best * n + i]) best = k // 1re occurrence du max
            val score = out[best * n + i]
            if (score < 0.2f) continue
            val cx = out[i]
            val cy = out[n + i]
            val bw = out[2 * n + i]
            val bh = out[3 * n + i]
            var x = (cx - bw / 2f) * sx
            var y = (cy - bh / 2f) * sy
            var w = bw * sx
            var h = bh * sy
            x = max(0f, min(x, imageWidth.toFloat()))
            y = max(0f, min(y, imageHeight.toFloat()))
            w = min(w, imageWidth - x)
            h = min(h, imageHeight - y)
            boxes += floatArrayOf(x, y, w, h)
            scores += score
            classes += best - 4
        }
        return nms(boxes, scores, 0.25f, 0.45f).map { i ->
            val b = boxes[i]
            Detection(ALL_LABELS[classes[i]], scores[i], b[0].toInt(), b[1].toInt(), b[2].toInt(), b[3].toInt())
        }
    }

    /** cv2.dnn.NMSBoxes : tri stable par score décroissant (score > seuil), suppression si IoU > nmsThreshold. */
    fun nms(boxes: List<FloatArray>, scores: List<Float>, scoreThreshold: Float, nmsThreshold: Float): List<Int> {
        val order = scores.indices.filter { scores[it] > scoreThreshold }.sortedByDescending { scores[it] }
        val kept = ArrayList<Int>()
        for (i in order) {
            if (kept.none { iou(boxes[i], boxes[it]) > nmsThreshold }) kept += i
        }
        return kept
    }

    private fun iou(a: FloatArray, b: FloatArray): Double {
        val ix = min(a[0].toDouble() + a[2], b[0].toDouble() + b[2]) - max(a[0], b[0])
        val iy = min(a[1].toDouble() + a[3], b[1].toDouble() + b[3]) - max(a[1], b[1])
        if (ix <= 0 || iy <= 0) return 0.0
        val inter = ix * iy
        val union = a[2].toDouble() * a[3] + b[2].toDouble() * b[3] - inter
        return if (union <= 0) 0.0 else inter / union
    }

    // ------------------------------------------------------------------ legnome : zones

    /** legnome _expand_box : marge relative, carré si demandé, recadrage dans l'image. */
    fun expandBox(x: Int, y: Int, w: Int, h: Int, padding: Double, square: Boolean, frameW: Int, frameH: Int): IntArray {
        val cx = x + w / 2.0
        val cy = y + h / 2.0
        var ww = w * (1 + 2 * padding)
        var hh = h * (1 + 2 * padding)
        if (square) {
            ww = max(ww, hh)
            hh = ww
        }
        val x0 = max(0, Math.rint(cx - ww / 2).toInt()) // round() de Python = arrondi au pair
        val y0 = max(0, Math.rint(cy - hh / 2).toInt())
        val x1 = min(frameW, Math.rint(cx + ww / 2).toInt())
        val y1 = min(frameH, Math.rint(cy + hh / 2).toInt())
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    /** Filtre (classes, seuil) et agrandit les détections d'une image, comme detect_video de legnome. */
    fun select(dets: List<Detection>, opts: Options, frameW: Int, frameH: Int): List<Box> = dets.mapNotNull { d ->
        if (d.label !in opts.labels || d.score < opts.threshold) return@mapNotNull null
        val (x, y, w, h) = expandBox(d.x, d.y, d.w, d.h, opts.padding, opts.square, frameW, frameH).toList()
        if (w <= 0 || h <= 0) null else Box(d.label, Math.round(d.score * 1000.0) / 1000.0, x, y, w, h)
    }

    /**
     * legnome active_boxes : zones de toutes les images analysées à moins de [hold] images de [index].
     * Couvre les images non analysées et évite le "clignotement" de la censure.
     */
    fun <T> activeBoxes(index: Int, byFrame: Map<Int, List<T>>, step: Int, hold: Int): List<T> {
        val first = -Math.floorDiv(-(index - hold), step) * step // multiple de step >= index - hold
        val result = ArrayList<T>()
        var j = max(0, first)
        while (j <= index + hold) {
            byFrame[j]?.let { result += it }
            j += step
        }
        return result
    }

    // ------------------------------------------------------------------ JSON des zones (format legnome)

    class Zones(
        val video: String,
        val width: Int,
        val height: Int,
        val fps: Double,
        val frameCount: Int,
        val step: Int,
        val threshold: Float,
        val padding: Double,
        val square: Boolean,
        val labels: List<String>,
        /** seules les images avec au moins une zone */
        val frames: Map<Int, List<Box>>,
    ) {
        fun toJson(): String {
            val fr = JSONArray()
            frames.toSortedMap().forEach { (idx, boxes) ->
                val arr = JSONArray()
                boxes.forEach { b ->
                    arr.put(JSONObject().put("label", b.label).put("score", b.score).put("x", b.x).put("y", b.y).put("w", b.w).put("h", b.h))
                }
                fr.put(JSONObject().put("frame", idx).put("time", Math.round(idx / fps * 1000.0) / 1000.0).put("boxes", arr))
            }
            return JSONObject()
                .put("video", video).put("width", width).put("height", height).put("fps", fps)
                .put("frame_count", frameCount).put("step", step).put("threshold", threshold.toDouble())
                .put("padding", padding).put("square", square).put("labels", JSONArray(labels)).put("frames", fr)
                .toString(1)
        }

        companion object {
            fun fromJson(text: String): Zones {
                val o = JSONObject(text)
                val frames = LinkedHashMap<Int, List<Box>>()
                val fr = o.optJSONArray("frames") ?: JSONArray()
                for (i in 0 until fr.length()) {
                    val f = fr.getJSONObject(i)
                    val arr = f.getJSONArray("boxes")
                    frames[f.getInt("frame")] = (0 until arr.length()).map { j ->
                        val b = arr.getJSONObject(j)
                        Box(b.optString("label"), b.optDouble("score", 1.0), b.getInt("x"), b.getInt("y"), b.getInt("w"), b.getInt("h"))
                    }
                }
                val labels = o.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: DEFAULT_LABELS
                return Zones(
                    o.optString("video"), o.getInt("width"), o.getInt("height"), o.optDouble("fps", 25.0),
                    o.optInt("frame_count", 0), o.optInt("step", 1), o.optDouble("threshold", 0.4).toFloat(),
                    o.optDouble("padding", 0.15), o.optBoolean("square", false), labels, frames,
                )
            }
        }
    }

    /** Arrondi vers le bas utile pour les tailles d'encodeur (paires). */
    fun even(v: Int) = v - (v and 1)
}
