package fr.perso.redgifsdl

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.security.MessageDigest

/**
 * Extracteur "n'importe quel lien" : trouve les vidéos d'une page web quelconque.
 *
 * Inspiré de l'extracteur "generic" de yt-dlp (yt_dlp/extractor/generic.py, common.py
 * _parse_html5_media_entries) et de jsoup (lecture du HTML par sélecteurs CSS). Dans l'ordre :
 *  1. lien direct vers une vidéo (type MIME / extension) ou un flux HLS ;
 *  2. balises <video>, <amp-video>, <source> (src, data-src, data-video-src, data-source, poster) ;
 *  3. JSON-LD schema.org VideoObject (contentUrl, embedUrl, thumbnailUrl, name) ;
 *  4. balises meta og:video, twitter:player:stream ;
 *  5. lecteurs JS courants (JW Player, Flowplayer, "file:" / "video_url:") ;
 *  6. toute adresse .mp4 / .m3u8 / .webm / .mov dans le code source (y compris "https:\/\/…") ;
 *  7. lecteurs intégrés (iframe, twitter:player) d'un site déjà pris en charge -> extracteur de ce site ;
 *  8. redirection <meta http-equiv="refresh">.
 * Les éléments trouvés sont proposés dans l'aperçu, où l'on décoche ce qu'on ne veut pas.
 */
object GenericExtractor : Extractor {

    private const val MAX_PAGE = 8 * 1024 * 1024
    private const val MAX_ITEMS = 100
    private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mov", "m3u8")
    // Faux positifs écartés (comme check_video de yt-dlp)
    private val NOT_VIDEO_EXT = setOf("swf", "png", "jpg", "jpeg", "gif", "webp", "srt", "sbv", "sub", "vtt", "ttml", "js", "css", "xml", "json", "html")

    private val RAW_URL = Regex(
        """https?:(?:\\?/){2}[^\s"'<>()\\]+?\.(?:mp4|m4v|webm|mov|m3u8)(?:\?[^\s"'<>]*)?(?=["'\s<>)\\]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val PLAYER_PATTERNS = listOf(
        // JW Player : jwplayer("x").setup({ file: "…" }) / sources: [{ file: "…" }]
        Regex("""(?s)(?:jw_plugins|JWPlayerOptions|jwplayer\s*\(\s*["'][^'"]+["']\s*\)\s*\.setup).*?['"]?file['"]?\s*:\s*["'](.*?)["']"""),
        Regex("""flashvars:\s*['"](?:.*&)?file=(http[^'"&]*)"""),
        Regex("""[^A-Za-z0-9]?(?:file|source)=(http[^'"&]*)"""),
        Regex("""[^A-Za-z0-9]?(?:file|video_url)["']?:\s*["'](http[^'"]+)["']"""),
        // Flowplayer
        Regex("""(?s)flowplayer\("[^"]+",\s*\{[^}]+?\}\s*,\s*\{[^}]+?["']?clip["']?\s*:\s*\{\s*["']?url["']?\s*:\s*["']([^"']+)["']"""),
    )

    private class Candidate(val url: String, var thumb: String?, var title: String?)

    override fun resolve(url: String): List<Resolved> = resolve(url, followRefresh = true)

    private fun resolve(url: String, followRefresh: Boolean): List<Resolved> {
        // 1. Lien direct ?
        val c = Http.openOk(url, mapOf("Accept" to "text/html,application/xhtml+xml,video/*,*/*;q=0.8"))
        val finalUrl: String
        val html: String
        try {
            finalUrl = c.url.toString()
            val type = (c.contentType ?: "").lowercase()
            val ext = extOf(finalUrl)
            if (type.startsWith("video/") || (ext in VIDEO_EXT && ext != "m3u8" && !type.contains("html"))) {
                return listOf(direct(finalUrl, null, fileTitle(finalUrl), null))
            }
            if (type.contains("mpegurl") || ext == "m3u8") {
                return listOf(resolved(finalUrl, fileTitle(finalUrl), null, null))
            }
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("text") && !type.contains("xml")) {
                throw RedgifsApi.ApiException("Ce lien n'est ni une page web ni une vidéo ($type)")
            }
            html = c.inputStream.use { input ->
                val bytes = input.readNBytesCompat(MAX_PAGE)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }

        val doc = Jsoup.parse(html, finalUrl)
        val pageTitle = meta(doc, "og:title") ?: doc.title().takeIf { it.isNotBlank() }
        val pageThumb = metaUrl(doc, "og:image") ?: metaUrl(doc, "twitter:image")
        val found = LinkedHashMap<String, Candidate>()
        /** [trusted] : source sûre d'être une vidéo (balise <video>, VideoObject…), même sans extension. */
        fun add(raw: String?, thumb: String? = null, title: String? = null, trusted: Boolean = false) {
            val u = normalize(raw, finalUrl) ?: return
            val ext = extOf(u)
            if (ext in NOT_VIDEO_EXT) return
            if (!trusted && ext !in VIDEO_EXT && !looksLikeVideo(u)) return
            val key = u.substringBefore('#')
            val existing = found[key]
            if (existing == null) {
                if (found.size < MAX_ITEMS) found[key] = Candidate(u, thumb, title)
            } else {
                if (existing.thumb == null) existing.thumb = thumb
                if (existing.title == null) existing.title = title
            }
        }

        // 2. Balises HTML5
        for (v in doc.select("video, amp-video, dl8-video, dl8-live-video")) {
            val poster = v.absUrl("poster").ifBlank { null }
            for (attr in listOf("src", "data-src", "data-video-src", "data-source")) add(v.attr(attr), poster, trusted = true)
            for (s in v.select("source")) {
                add(s.attr("src").ifBlank { s.attr("data-src") }, poster, trusted = true)
            }
        }
        // 3. JSON-LD VideoObject
        val delegates = LinkedHashSet<String>()
        for (s in doc.select("script[type=application/ld+json]")) {
            runCatching { videoObjects(s.data()) }.getOrDefault(emptyList()).forEach { o ->
                val thumb = thumbOf(o.opt("thumbnailUrl"))
                val name = o.optString("name").ifBlank { null }
                add(o.optString("contentUrl"), thumb, name, trusted = true)
                o.optString("embedUrl").takeIf { it.startsWith("http") }?.let { delegates += it }
            }
        }
        // 4. Meta
        // og:video seulement si son type est une vidéo (pas un lecteur Flash/HTML), comme yt-dlp
        val ogVideoIsVideo = meta(doc, "og:video:type")?.startsWith("video/") == true
        for (p in listOf("og:video:secure_url", "og:video:url", "og:video")) add(meta(doc, p), pageThumb, trusted = ogVideoIsVideo)
        add(meta(doc, "twitter:player:stream"), pageThumb, trusted = true)
        // 5. Lecteurs JS
        for (re in PLAYER_PATTERNS) re.findAll(html).forEach { add(it.groupValues[1], pageThumb) }
        // 6. Adresses brutes dans le code source (y compris échappées en JSON : https:\/\/…, /)
        val unescaped = html.replace("\\u002F", "/").replace("\\u002f", "/").replace("\\u0026", "&").replace("\\/", "/")
        RAW_URL.findAll(unescaped).forEach { add(it.value) }

        // 7. Lecteurs intégrés de sites pris en charge
        doc.select("iframe[src], embed[src], iframe[data-src]").forEach { el ->
            el.absUrl("src").ifBlank { el.absUrl("data-src") }.takeIf { it.startsWith("http") }?.let { delegates += it }
        }
        metaUrl(doc, "twitter:player")?.let { delegates += it }
        val fromDelegates = ArrayList<Resolved>()
        for (d in delegates.take(10)) {
            when (val t = Links.parse(d)) {
                is Links.Target.Page -> if (t.site != Site.WEB) {
                    runCatching { Extractors.forSite(t.site).resolve(t.url) }.getOrNull()?.let { fromDelegates += it }
                }
                is Links.Target.RedgifsSingle -> fromDelegates += Resolved(
                    t.id, pageTitle, listOf(Variant(null, Plan.Redgifs(t.id))), thumbnail = pageThumb,
                )
                else -> Unit
            }
        }

        if (found.isEmpty() && fromDelegates.isEmpty()) {
            // 8. Redirection <meta http-equiv="refresh" content="0; url=…">
            if (followRefresh) {
                doc.select("meta[http-equiv~=(?i)refresh]").firstOrNull()?.attr("content")
                    ?.let { Regex("""(?i)url\s*=\s*'?([^'"]+)""").find(it)?.groupValues?.get(1) }
                    ?.let { normalize(it, finalUrl) }
                    ?.takeIf { it != finalUrl }
                    ?.let { return resolve(it, followRefresh = false) }
            }
            throw RedgifsApi.ApiException(
                "Aucune vidéo trouvée sur cette page. Elle est peut-être chargée par JavaScript après l'ouverture, " +
                    "ou protégée (connexion, DRM)."
            )
        }

        val headers = mapOf("Referer" to finalUrl)
        val list = found.values.toList()
        val items = list.mapIndexed { i, cand ->
            val title = cand.title ?: when {
                list.size == 1 -> pageTitle
                else -> listOfNotNull(pageTitle, "(${i + 1})").joinToString(" ")
            } ?: fileTitle(cand.url)
            // Miniature de la page seulement si une seule vidéo (sinon elle serait trompeuse)
            val thumb = cand.thumb ?: pageThumb.takeIf { list.size == 1 }
            resolved(cand.url, title, thumb, headers)
        }
        return fromDelegates + items
    }

    /** Un élément trouvé -> variantes (HLS : qualités de la playlist ; sinon fichier direct). */
    private fun resolved(url: String, title: String?, thumb: String?, headers: Map<String, String>?): Resolved {
        if (extOf(url) == "m3u8") {
            val h = headers ?: emptyMap()
            val variants = runCatching { Extractors.hlsVariants(url, h) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: listOf(Variant(null, Plan.Hls(url, null, h)))
            return Resolved(idOf(url), title, variants, thumbnail = thumb)
        }
        return direct(url, headers, title, thumb)
    }

    private fun direct(url: String, headers: Map<String, String>?, title: String?, thumb: String?): Resolved {
        val ext = extOf(url).takeIf { it in setOf("mp4", "m4v", "webm", "mov") } ?: "mp4"
        return Resolved(idOf(url), title, listOf(Variant(heightOf(url), Plan.Direct(url, headers ?: emptyMap(), ext))), thumbnail = thumb)
    }

    // ------------------------------------------------------------------ utilitaires (testés sur PC)

    /** Identifiant stable : empreinte de l'adresse sans paramètres (souvent des jetons qui expirent). */
    fun idOf(url: String): String {
        val base = url.substringBefore('?').substringBefore('#')
        val md = MessageDigest.getInstance("SHA-1").digest(base.toByteArray())
        return md.take(6).joinToString("") { "%02x".format(it) }
    }

    fun extOf(url: String): String =
        url.substringBefore('#').substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun heightOf(url: String): Int? =
        Regex("""(?i)(?:^|[^0-9])(2160|1440|1080|720|540|480|360|240|144)p""").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun looksLikeVideo(u: String) = Regex("""(?i)/(?:videos?|media|stream)/|\.(?:mp4|webm|m3u8)""").containsMatchIn(u)

    private fun fileTitle(url: String) = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifBlank { null }

    /** Adresse absolue nettoyée (échappements HTML/JSON), ou null si ce n'est pas une adresse http(s). */
    fun normalize(raw: String?, base: String): String? {
        if (raw.isNullOrBlank()) return null
        val s = Http.decodeEntities(raw.trim()).replace("\\/", "/")
        if (s.startsWith("data:") || s.startsWith("blob:") || s.startsWith("javascript:")) return null
        val abs = runCatching { URI(base).resolve(s.replace(" ", "%20")).toString() }.getOrNull() ?: return null
        return abs.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** Contenu brut d'une balise meta (texte). */
    private fun meta(doc: Document, name: String): String? =
        doc.select("meta[property=$name], meta[name=$name]").firstOrNull()?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

    /** Contenu d'une balise meta qui est une adresse (rendue absolue). */
    private fun metaUrl(doc: Document, name: String): String? = normalize(meta(doc, name), doc.location())

    /** Objets VideoObject d'un bloc JSON-LD (objet, tableau ou @graph). */
    fun videoObjects(json: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun visit(v: Any?) {
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) visit(v.opt(i))
                is JSONObject -> {
                    val type = v.opt("@type")
                    val isVideo = type == "VideoObject" || (type is JSONArray && (0 until type.length()).any { type.opt(it) == "VideoObject" })
                    if (isVideo) out += v
                    visit(v.opt("@graph"))
                    visit(v.opt("video"))
                }
            }
        }
        val t = json.trim()
        visit(if (t.startsWith("[")) JSONArray(t) else JSONObject(t))
        return out
    }

    private fun thumbOf(v: Any?): String? = when (v) {
        is String -> v.takeIf { it.startsWith("http") }
        is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { thumbOf(v.opt(it)) }
        is JSONObject -> v.optString("url").takeIf { it.startsWith("http") }
        else -> null
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
