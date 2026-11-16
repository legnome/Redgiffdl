package fr.perso.redgifsdl

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Téléchargement de flux HLS (.m3u8) : playlist maître -> variantes, playlist média -> segments.
 * Gère les segments MPEG-TS et fMP4 (#EXT-X-MAP), le chiffrement AES-128 et #EXT-X-BYTERANGE.
 */
object Hls {

    data class Variant(
        val url: String,
        /** côté court de l'image (720 pour 1280x720 comme pour 720x1280), si connu */
        val height: Int?,
        val bandwidth: Long,
        /** piste audio séparée (#EXT-X-MEDIA TYPE=AUDIO), si la variante n'a pas le son intégré */
        val audioUrl: String?,
    )

    private val ATTR = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    /** Variantes d'une playlist maître (triées de la meilleure à la moins bonne). Playlist média -> 1 variante. */
    fun variants(url: String, headers: Map<String, String>): List<Variant> =
        parseVariants(url, Http.getText(url, headers))

    fun parseVariants(url: String, text: String): List<Variant> {
        if (!isMaster(text)) return listOf(Variant(url, null, 0, null))
        val audioGroups = HashMap<String, String>()
        val lines = text.lines().map { it.trim() }
        for (l in lines) {
            if (l.startsWith("#EXT-X-MEDIA:")) {
                val a = attrs(l)
                val uri = a["URI"] ?: continue
                if (a["TYPE"] != "AUDIO") continue
                val group = a["GROUP-ID"] ?: continue
                if (group !in audioGroups || a["DEFAULT"] == "YES") audioGroups[group] = Http.resolve(url, uri)
            }
        }
        val result = ArrayList<Variant>()
        for ((i, l) in lines.withIndex()) {
            if (!l.startsWith("#EXT-X-STREAM-INF:")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val a = attrs(l)
            val height = a["RESOLUTION"]?.split('x')?.mapNotNull { it.toIntOrNull() }
                ?.takeIf { it.size == 2 }?.min()
            result += Variant(
                Http.resolve(url, uri),
                height,
                a["BANDWIDTH"]?.toLongOrNull() ?: 0,
                a["AUDIO"]?.let { audioGroups[it] },
            )
        }
        return result.sortedWith(compareByDescending<Variant> { it.height ?: 0 }.thenByDescending { it.bandwidth })
    }

    private class Segment(val url: String, val range: LongRange?, val key: Key?, val sequence: Long)
    private class Key(val uri: String, val iv: ByteArray?)

    /**
     * Télécharge une playlist (maître : meilleure variante ; média : telle quelle) dans [out].
     * @return true si les segments sont en fMP4 (fichier MP4 directement lisible), false si MPEG-TS.
     */
    fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        out: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Boolean {
        var url = playlistUrl
        var text = Http.getText(url, headers)
        if (isMaster(text)) {
            url = parseVariants(url, text).first().url
            text = Http.getText(url, headers)
        }
        val lines = text.lines().map { it.trim() }
        var sequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            ?.substringAfter(':')?.toLongOrNull() ?: 0L
        var initUrl: String? = null
        var initRange: LongRange? = null
        var key: Key? = null
        var pendingRange: Pair<Long, Long?>? = null // longueur, décalage
        val lastEnd = HashMap<String, Long>()
        val segments = ArrayList<Segment>()
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(l)
                    initUrl = a["URI"]?.let { Http.resolve(url, it) }
                    initRange = a["BYTERANGE"]?.let { br ->
                        val len = br.substringBefore('@').toLong()
                        val off = br.substringAfter('@', "0").toLong()
                        off until off + len
                    }
                }
                l.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(l)
                    key = when (a["METHOD"]) {
                        "NONE", null -> null
                        "AES-128" -> Key(
                            Http.resolve(url, a["URI"] ?: throw IOException("clé HLS sans URI")),
                            a["IV"]?.removePrefix("0x")?.removePrefix("0X")?.let(::hexToBytes),
                        )
                        else -> throw IOException("Chiffrement HLS non pris en charge (${a["METHOD"]})")
                    }
                }
                l.startsWith("#EXT-X-BYTERANGE:") -> {
                    val v = l.substringAfter(':')
                    pendingRange = v.substringBefore('@').toLong() to v.substringAfter('@', "").toLongOrNull()
                }
                l.isNotEmpty() && !l.startsWith("#") -> {
                    val segUrl = Http.resolve(url, l)
                    val range = pendingRange?.let { (len, off) ->
                        val start = off ?: lastEnd[segUrl] ?: 0L
                        lastEnd[segUrl] = start + len
                        start until start + len
                    }
                    pendingRange = null
                    segments += Segment(segUrl, range, key, sequence++)
                }
            }
        }
        if (segments.isEmpty()) throw IOException("Playlist HLS vide")

        val keyCache = HashMap<String, ByteArray>()
        FileOutputStream(out).use { output ->
            initUrl?.let { output.write(fetch(it, initRange, headers)) }
            segments.forEachIndexed { i, seg ->
                var data = RedgifsApi.retrying { fetch(seg.url, seg.range, headers) }
                seg.key?.let { k ->
                    val keyBytes = keyCache.getOrPut(k.uri) { RedgifsApi.retrying { Http.getBytes(k.uri, headers) } }
                    val iv = k.iv ?: ByteArray(16).also { b ->
                        var s = seg.sequence
                        for (j in 15 downTo 8) { b[j] = (s and 0xFF).toByte(); s = s shr 8 }
                    }
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
                    data = cipher.doFinal(data)
                }
                output.write(data)
                onProgress(i + 1, segments.size)
            }
        }
        return initUrl != null
    }

    private fun fetch(url: String, range: LongRange?, headers: Map<String, String>): ByteArray =
        if (range == null) Http.getBytes(url, headers)
        else Http.getBytes(url, headers + ("Range" to "bytes=${range.first}-${range.last}"))

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.padStart(32, '0')
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
