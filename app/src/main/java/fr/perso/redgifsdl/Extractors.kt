package fr.perso.redgifsdl

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Ce qu'un extracteur trouve sur une page : une ou plusieurs vidéos, chacune en plusieurs qualités. */
class Resolved(
    /** identifiant stable (sert à détecter les doublons) */
    val id: String,
    val title: String?,
    /** de la meilleure à la moins bonne qualité */
    val variants: List<Variant>,
)

class Variant(val height: Int?, val plan: Plan)

/** Comment récupérer le fichier. */
sealed class Plan {
    data class Direct(val url: String, val headers: Map<String, String> = emptyMap(), val ext: String = "mp4") : Plan()
    data class Hls(val url: String, val audioUrl: String?, val headers: Map<String, String> = emptyMap()) : Plan()
    /** vidéo et audio séparés (Reddit), réunis ensuite */
    data class Split(
        val videoUrl: String,
        val audioUrl: String?,
        val headers: Map<String, String> = emptyMap(),
        /** tailles connues à l'avance (YouTube) : téléchargement par morceaux */
        val videoSize: Long? = null,
        val audioSize: Long? = null,
    ) : Plan()
    /** vidéo RedGIFs (via l'API à jeton) */
    data class Redgifs(val id: String) : Plan()
}

interface Extractor {
    fun resolve(url: String): List<Resolved>
}

object Extractors {
    fun forSite(site: Site): Extractor = when (site) {
        Site.XVIDEOS, Site.XNXX -> XvideosExtractor
        Site.PORNHUB -> PornhubExtractor
        Site.TWITTER -> TwitterExtractor
        Site.REDDIT -> RedditExtractor
        Site.YOUTUBE -> YoutubeExtractor
        Site.REDGIFS -> throw IllegalArgumentException("RedGIFs passe par RedgifsApi")
    }

    /** Choix de la variante selon la qualité demandée. */
    fun pick(variants: List<Variant>, quality: Quality): Variant {
        require(variants.isNotEmpty())
        if (quality == Quality.MAX) return variants.first()
        val known = variants.filter { it.height != null }
        known.filter { it.height!! <= quality.maxHeight }.maxByOrNull { it.height!! }?.let { return it }
        variants.firstOrNull { it.height == null }?.let { return it }
        return known.minBy { it.height!! }
    }

    /** Trie les variantes : hauteur décroissante (inconnue à la fin), ordre d'origine sinon. */
    fun sorted(list: List<Variant>) = list.sortedByDescending { it.height ?: -1 }

    fun hlsVariants(master: String, headers: Map<String, String>): List<Variant> =
        Hls.variants(master, headers).map { Variant(it.height, Plan.Hls(it.url, it.audioUrl, headers)) }
}

private fun err(msg: String): Nothing = throw RedgifsApi.ApiException(msg)

// ---------------------------------------------------------------- XVideos / XNXX

/**
 * Les pages contiennent : html5player.setVideoTitle('…'), setVideoUrlLow('…mp4'),
 * setVideoUrlHigh('…mp4') et setVideoHLS('…m3u8') ; le HLS a les meilleures qualités (jusqu'à 1080p/4K).
 */
object XvideosExtractor : Extractor {
    internal fun js(html: String, fn: String) =
        Regex("""html5player\.$fn\(\s*'((?:\\'|[^'])*)'""").find(html)?.groupValues?.get(1)?.replace("\\'", "'")

    override fun resolve(url: String): List<Resolved> {
        val headers = mapOf("Cookie" to "disclaimer_display=1")
        val html = Http.getText(url, headers)
        val path = URI(url).path ?: ""
        val id = Regex("""/video[.-]?([a-z0-9]+)""", RegexOption.IGNORE_CASE).find(path)?.groupValues?.get(1)
            ?: path.trim('/').substringBefore('/')
        val title = js(html, "setVideoTitle")
            ?: Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.let(Http::decodeEntities)

        val variants = ArrayList<Variant>()
        js(html, "setVideoHLS")?.let { hls ->
            runCatching { variants += Extractors.hlsVariants(hls, emptyMap()) }
        }
        // MP4 directs : "High" ≈ 360-480p, "Low" ≈ 240p (moins bons que le HLS, mais toujours là en secours)
        js(html, "setVideoUrlHigh")?.let { variants += Variant(360, Plan.Direct(it)) }
        js(html, "setVideoUrlLow")?.let { variants += Variant(240, Plan.Direct(it)) }
        if (variants.isEmpty()) {
            err("Aucune vidéo trouvée sur la page (vidéo supprimée, privée, ou page de vérification d'âge)")
        }
        return listOf(Resolved(id, title, Extractors.sorted(variants)))
    }
}

// ---------------------------------------------------------------- Pornhub

/**
 * La page contient "var flashvars_XXXX = {...}" avec "mediaDefinitions" : des flux HLS par qualité,
 * et un lien "get_media" qui renvoie la liste des MP4 directs.
 */
object PornhubExtractor : Extractor {
    private val HEADERS = mapOf(
        "Cookie" to "age_verified=1; accessAgeDisclaimerPH=1; accessAgeDisclaimerUK=1; accessPH=1; platform=pc",
    )

    override fun resolve(url: String): List<Resolved> {
        val html = Http.getText(url, HEADERS)
        val viewkey = Regex("""viewkey=([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: err("Lien Pornhub sans viewkey")
        val title = Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)
            ?.let(Http::decodeEntities)

        val defs = mediaDefinitions(html) ?: run {
            val blocked = listOf("not available in your country", "pas disponible dans votre pays", "age verification", "vérification de l'âge")
                .any { html.contains(it, ignoreCase = true) }
            err(
                if (blocked) "Pornhub bloque cette vidéo depuis ton pays / sans vérification d'âge (essaie avec un VPN)"
                else "Aucune vidéo trouvée sur la page Pornhub (supprimée, privée ou page modifiée)"
            )
        }

        val variants = ArrayList<Variant>()
        for (i in 0 until defs.length()) {
            val d = defs.optJSONObject(i) ?: continue
            val videoUrl = d.optString("videoUrl").takeIf { it.startsWith("http") } ?: continue
            when (d.optString("format")) {
                "mp4" -> if (d.optBoolean("remote") || videoUrl.contains("get_media")) {
                    runCatching {
                        val list = JSONArray(Http.getText(videoUrl, HEADERS))
                        for (j in 0 until list.length()) {
                            val m = list.optJSONObject(j) ?: continue
                            val u = m.optString("videoUrl").takeIf { it.startsWith("http") } ?: continue
                            variants += Variant(qualityOf(m.opt("quality")), Plan.Direct(u, HEADERS))
                        }
                    }
                } else {
                    variants += Variant(qualityOf(d.opt("quality")), Plan.Direct(videoUrl, HEADERS))
                }
                "hls" -> variants += Variant(qualityOf(d.opt("quality")), Plan.Hls(videoUrl, null, HEADERS))
            }
        }
        if (variants.isEmpty()) err("Aucun flux vidéo exploitable sur la page Pornhub")
        // À hauteur égale, MP4 direct (plus simple) avant HLS
        val sorted = variants.sortedWith(
            compareByDescending<Variant> { it.height ?: -1 }.thenBy { if (it.plan is Plan.Direct) 0 else 1 }
        )
        return listOf(Resolved(viewkey, title, sorted))
    }

    /** Extrait le tableau JSON "mediaDefinitions" (comptage des crochets). */
    internal fun mediaDefinitions(html: String): JSONArray? {
        val key = html.indexOf("\"mediaDefinitions\"")
        if (key < 0) return null
        val start = html.indexOf('[', key)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> if (--depth == 0) return runCatching { JSONArray(html.substring(start, i + 1)) }.getOrNull()
            }
            i++
        }
        return null
    }

    private fun qualityOf(q: Any?): Int? = when (q) {
        is Number -> q.toInt()
        is String -> q.filter { it.isDigit() }.toIntOrNull()
        is JSONArray -> (0 until q.length()).mapNotNull { qualityOf(q.opt(it)) }.maxOrNull()
        else -> null
    }
}

// ---------------------------------------------------------------- Twitter / X

/**
 * 1. API "syndication" de Twitter (celle des tweets intégrés, sans compte)
 * 2. secours : API publiques fxtwitter puis vxtwitter (utile pour les tweets marqués "sensibles")
 */
object TwitterExtractor : Extractor {

    private class Vid(val variants: List<Variant>)
    private class Found(val text: String?, val user: String?, val videos: List<Vid>)

    override fun resolve(url: String): List<Resolved> {
        val id = Links.TWEET_ID.find(URI(url).path ?: "")?.groupValues?.get(1) ?: err("Lien Twitter sans identifiant de tweet")
        val errors = ArrayList<String>()
        val found = listOf(::syndication, ::fxtwitter, ::vxtwitter).firstNotNullOfOrNull { source ->
            runCatching { source(id) }.onFailure { errors += it.message ?: it.javaClass.simpleName }.getOrNull()
                ?.takeIf { it.videos.isNotEmpty() }
        } ?: err("Aucune vidéo trouvée dans ce tweet (tweet supprimé, privé, ou sans vidéo)")

        val title = listOfNotNull(found.user?.let { "@$it" }, found.text?.take(60)).joinToString(" ").ifBlank { null }
        return found.videos.mapIndexed { i, v ->
            Resolved(if (found.videos.size > 1) "$id-${i + 1}" else id, title, v.variants)
        }
    }

    private fun heightFromUrl(u: String): Int? =
        Regex("""/(\d{2,4})x(\d{2,4})/""").find(u)?.let { minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    private fun mp4Variants(arr: JSONArray?, urlKey: String = "url", typeKey: String = "content_type"): List<Variant> {
        if (arr == null) return emptyList()
        val list = ArrayList<Pair<Long, String>>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val u = v.optString(urlKey).takeIf { it.startsWith("http") } ?: continue
            if (v.optString(typeKey, "video/mp4") != "video/mp4" && !u.contains(".mp4")) continue
            list += v.optLong("bitrate", 0) to u
        }
        return list.sortedByDescending { it.first }.map { Variant(heightFromUrl(it.second), Plan.Direct(it.second)) }
    }

    private fun syndication(id: String): Found {
        val token = syndicationToken(id)
        val json = JSONObject(Http.getText("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=fr&token=$token"))
        val details = json.optJSONArray("mediaDetails") ?: JSONArray()
        val videos = (0 until details.length()).mapNotNull { i ->
            val m = details.optJSONObject(i) ?: return@mapNotNull null
            if (m.optString("type") !in setOf("video", "animated_gif")) return@mapNotNull null
            mp4Variants(m.optJSONObject("video_info")?.optJSONArray("variants")).takeIf { it.isNotEmpty() }?.let(::Vid)
        }
        return Found(json.optString("text").ifBlank { null }, json.optJSONObject("user")?.optString("screen_name"), videos)
    }

    private fun fxtwitter(id: String): Found {
        val tweet = JSONObject(Http.getText("https://api.fxtwitter.com/status/$id")).optJSONObject("tweet")
            ?: err("fxtwitter : tweet introuvable")
        val arr = tweet.optJSONObject("media")?.optJSONArray("videos") ?: JSONArray()
        val videos = (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            val fromVariants = mp4Variants(v.optJSONArray("variants"))
            val list = fromVariants.ifEmpty {
                val u = v.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val h = minOf(v.optInt("width", 0), v.optInt("height", 0)).takeIf { it > 0 }
                listOf(Variant(h, Plan.Direct(u)))
            }
            Vid(list)
        }
        return Found(tweet.optString("text").ifBlank { null }, tweet.optJSONObject("author")?.optString("screen_name"), videos)
    }

    private fun vxtwitter(id: String): Found {
        val json = JSONObject(Http.getText("https://api.vxtwitter.com/Twitter/status/$id"))
        val arr = json.optJSONArray("media_extended") ?: JSONArray()
        val videos = (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            if (m.optString("type") !in setOf("video", "gif")) return@mapNotNull null
            val u = m.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val size = m.optJSONObject("size")
            val h = size?.let { minOf(it.optInt("width", 0), it.optInt("height", 0)) }?.takeIf { it > 0 }
            Vid(listOf(Variant(h, Plan.Direct(u))))
        }
        return Found(json.optString("text").ifBlank { null }, json.optString("user_screen_name").ifBlank { null }, videos)
    }

    /** Jeton exigé par l'API syndication : ((id / 1e15) * π).toString(36) sans les 0 ni le point (calcul JavaScript). */
    fun syndicationToken(id: String): String =
        jsToString36((id.toDouble() / 1e15) * Math.PI).replace(Regex("(0+|\\.)"), "")

    /** Reproduction de Number.prototype.toString(36) de JavaScript (algorithme de V8) pour un nombre positif. */
    fun jsToString36(value: Double): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        var integer = Math.floor(value)
        var fraction = value - integer
        var delta = maxOf(0.5 * (Math.nextUp(value) - value), Math.nextUp(0.0))
        val frac = StringBuilder()
        if (fraction >= delta) {
            do {
                fraction *= 36
                delta *= 36
                val digit = fraction.toInt()
                frac.append(chars[digit])
                fraction -= digit
                if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                    if (fraction + delta > 1) {
                        // arrondi au supérieur avec propagation de la retenue
                        while (true) {
                            if (frac.isEmpty()) {
                                integer += 1
                                break
                            }
                            val d = chars.indexOf(frac[frac.length - 1])
                            if (d + 1 < 36) {
                                frac.setCharAt(frac.length - 1, chars[d + 1])
                                break
                            }
                            frac.setLength(frac.length - 1)
                        }
                        break
                    }
                }
            } while (fraction >= delta)
        }
        val intPart = StringBuilder()
        var n = integer
        do {
            val r = n % 36
            intPart.insert(0, chars[r.toInt()])
            n = (n - r) / 36
        } while (n > 0)
        return if (frac.isEmpty()) intPart.toString() else "$intPart.$frac"
    }
}

// ---------------------------------------------------------------- Reddit

/**
 * API JSON publique de Reddit (URL du post + ".json").
 * Vidéos hébergées sur Reddit : flux DASH (vidéo et audio séparés, réunis ensuite).
 * Posts qui pointent vers RedGIFs / Imgur / i.redd.it : récupérés aussi.
 */
object RedditExtractor : Extractor {
    private val HEADERS = mapOf("Cookie" to "over18=1")

    override fun resolve(url: String): List<Resolved> {
        var u = url
        val host = URI(u).host?.lowercase() ?: ""
        // Liens courts (redd.it, v.redd.it, /r/x/s/…) : suivre la redirection jusqu'au post
        if (host == "redd.it" || host == "v.redd.it" || Regex("""/s/[A-Za-z0-9]+""").containsMatchIn(URI(u).path ?: "")) {
            u = Http.finalUrl(u, HEADERS)
        }
        val path = URI(u).path?.trimEnd('/') ?: err("Lien Reddit invalide")
        if (!path.contains("/comments/")) err("Ce lien Reddit n'est pas un post (il faut un lien …/comments/…)")
        val json = try {
            JSONArray(Http.getText("https://www.reddit.com$path.json?raw_json=1", HEADERS))
        } catch (e: RedgifsApi.HttpError) {
            if (e.code == 403 || e.code == 429) err("Reddit refuse la requête (HTTP ${e.code}) : réessaie plus tard")
            throw e
        }
        var post = json.getJSONObject(0).getJSONObject("data").getJSONArray("children")
            .getJSONObject(0).getJSONObject("data")
        post.optJSONArray("crosspost_parent_list")?.optJSONObject(0)?.let { post = it }

        val id = post.optString("id")
        val title = post.optString("title").ifBlank { null }

        // 1. Vidéo hébergée par Reddit
        val rv = post.optJSONObject("secure_media")?.optJSONObject("reddit_video")
            ?: post.optJSONObject("media")?.optJSONObject("reddit_video")
            ?: post.optJSONObject("preview")?.optJSONObject("reddit_video_preview")
        if (rv != null) return listOf(Resolved(id, title, redditVideo(rv)))

        // 2. Galerie (images / GIF animés)
        if (post.optBoolean("is_gallery")) {
            val meta = post.optJSONObject("media_metadata")
            val items = post.optJSONObject("gallery_data")?.optJSONArray("items")
            if (meta != null && items != null) {
                val list = (0 until items.length()).mapNotNull { i ->
                    val mid = items.optJSONObject(i)?.optString("media_id") ?: return@mapNotNull null
                    val s = meta.optJSONObject(mid)?.optJSONObject("s") ?: return@mapNotNull null
                    val mp4 = s.optString("mp4").takeIf { it.startsWith("http") }
                    val img = s.optString("u").takeIf { it.startsWith("http") } ?: s.optString("gif").takeIf { it.startsWith("http") }
                    val plan = when {
                        mp4 != null -> Plan.Direct(mp4)
                        img != null -> Plan.Direct(img, ext = extOf(img, "jpg"))
                        else -> return@mapNotNull null
                    }
                    Resolved("$id-${i + 1}", title, listOf(Variant(null, plan)))
                }
                if (list.isNotEmpty()) return list
            }
        }

        // 3. Lien externe
        val link = post.optString("url_overridden_by_dest").ifBlank { post.optString("url") }
        RedgifsApi.parse(link)?.let { t ->
            if (t is RedgifsApi.Target.Single) return listOf(Resolved(id, title, listOf(Variant(null, Plan.Redgifs(t.id)))))
        }
        val linkHost = runCatching { URI(link).host?.lowercase() }.getOrNull() ?: ""
        when {
            linkHost.endsWith("imgur.com") && link.endsWith(".gifv") ->
                return listOf(Resolved(id, title, listOf(Variant(null, Plan.Direct(link.removeSuffix(".gifv") + ".mp4")))))
            linkHost == "i.redd.it" || linkHost.endsWith("imgur.com") && Regex("""\.(mp4|gif|jpe?g|png)$""").containsMatchIn(link) ->
                return listOf(Resolved(id, title, listOf(Variant(null, Plan.Direct(link, ext = extOf(link, "jpg"))))))
        }
        err("Pas de vidéo dans ce post Reddit" + (if (linkHost.isNotEmpty()) " (lien vers $linkHost)" else ""))
    }

    private fun extOf(url: String, default: String) =
        url.substringBefore('?').substringAfterLast('.', default).lowercase().takeIf { it.length in 2..4 } ?: default

    private fun redditVideo(rv: JSONObject): List<Variant> {
        val fallback = rv.optString("fallback_url").takeIf { it.startsWith("http") }
        val dash = rv.optString("dash_url").takeIf { it.startsWith("http") }
        // Base "https://v.redd.it/<id>/"
        val base = (fallback ?: dash)?.substringBefore('?')?.substringBeforeLast('/')?.plus("/")
            ?: err("Vidéo Reddit sans URL")

        if (dash != null) {
            runCatching { parseMpd(Http.getText(dash, HEADERS), base) }.getOrNull()?.let { (videos, audio) ->
                if (videos.isNotEmpty()) {
                    return videos.sortedByDescending { it.first }.map { (h, v) -> Variant(h, Plan.Split(v, audio, HEADERS)) }
                }
            }
        }
        // Secours : fallback_url (sans son) + fichier audio deviné
        val video = fallback ?: err("Vidéo Reddit sans flux exploitable")
        val audio = listOf("DASH_AUDIO_128.mp4", "DASH_AUDIO_64.mp4", "DASH_audio.mp4", "audio")
            .map { base + it }.firstOrNull { Http.exists(it, HEADERS) }
        val h = rv.optInt("height", 0).takeIf { it > 0 }?.let { minOf(it, rv.optInt("width", it)) }
        return listOf(Variant(h, Plan.Split(video, audio, HEADERS)))
    }

    /** @return (liste (hauteur, url vidéo), url audio ou null) */
    fun parseMpd(mpd: String, base: String): Pair<List<Pair<Int?, String>>, String?> {
        val videos = ArrayList<Pair<Int?, String>>()
        var audio: Pair<Long, String>? = null
        val repRe = Regex("""<Representation\b([^>]*)>(.*?)</Representation>""", RegexOption.DOT_MATCHES_ALL)
        fun attr(a: String, name: String) = Regex("""\b$name="([^"]*)"""").find(a)?.groupValues?.get(1)
        for (set in mpd.split("<AdaptationSet").drop(1)) {
            val head = set.substringBefore('>')
            val isAudio = head.contains("audio", ignoreCase = true)
            for (m in repRe.findAll(set)) {
                val a = m.groupValues[1]
                val baseUrl = Regex("""<BaseURL>([^<]+)</BaseURL>""").find(m.groupValues[2])?.groupValues?.get(1)?.trim() ?: continue
                val full = Http.resolve(base, URLDecoder.decode(baseUrl, "UTF-8"))
                val repIsAudio = isAudio || attr(a, "mimeType")?.startsWith("audio") == true
                if (repIsAudio) {
                    val bw = attr(a, "bandwidth")?.toLongOrNull() ?: 0
                    if (audio == null || bw > audio.first) audio = bw to full
                } else {
                    val w = attr(a, "width")?.toIntOrNull()
                    val h = attr(a, "height")?.toIntOrNull()
                    videos += (if (w != null && h != null) minOf(w, h) else h) to full
                }
            }
        }
        return videos to audio?.second
    }
}

// ---------------------------------------------------------------- YouTube

/**
 * API interne de YouTube ("InnerTube", /youtubei/v1/player), en se présentant comme une appli officielle
 * dont les adresses de téléchargement ne sont pas chiffrées (pas besoin d'exécuter le JavaScript de YouTube).
 * Plusieurs applis sont essayées dans l'ordre, et l'adresse obtenue est testée avant d'être retenue.
 *
 * Formats : vidéo H.264 (jusqu'à 1080p) + son AAC, téléchargés séparément puis réunis en MP4 ;
 * secours : MP4 tout-en-un 360p, ou flux HLS (appli iOS).
 *
 * YouTube change souvent ses protections : si ça casse, mettre à jour les versions de CLIENTS.
 */
object YoutubeExtractor : Extractor {

    private class Client(
        val name: String,
        val id: Int,
        val version: String,
        val userAgent: String,
        val extra: Map<String, Any>,
    )

    private val CLIENTS = listOf(
        Client(
            "ANDROID_VR", 28, "1.62.27",
            "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
        ),
        Client(
            "IOS", 5, "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
        ),
        Client(
            "ANDROID", 3, "20.10.38",
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            mapOf("androidSdkVersion" to 30, "osName" to "Android", "osVersion" to "11"),
        ),
    )

    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

    override fun resolve(url: String): List<Resolved> {
        val id = Links.youtubeId(url) ?: err("Lien YouTube sans identifiant de vidéo")
        val errors = ArrayList<String>()
        var visitorData: String? = null
        for (client in CLIENTS) {
            for (attempt in 0..1) {
                val hadVisitor = visitorData != null
                val json = try {
                    player(client, id, visitorData)
                } catch (e: Exception) {
                    errors += "${client.name} : ${e.message ?: e.javaClass.simpleName}"
                    break
                }
                json.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }
                    ?.let { visitorData = it }
                val status = json.optJSONObject("playabilityStatus")
                if (status?.optString("status") != "OK") {
                    errors += "${client.name} : " + (status?.optString("reason")?.takeIf { it.isNotBlank() } ?: status?.optString("status") ?: "réponse vide")
                    // Un 2e essai avec l'identifiant de visiteur obtenu peut lever la vérification "robot"
                    if (attempt == 0 && !hadVisitor && visitorData != null) continue
                    break
                }
                val details = json.optJSONObject("videoDetails")
                if (details?.optBoolean("isLive") == true) err("Les directs YouTube ne sont pas pris en charge")
                val variants = json.optJSONObject("streamingData")?.let { variantsFrom(it, client) } ?: emptyList()
                if (variants.isEmpty()) {
                    errors += "${client.name} : aucun format téléchargeable"
                    break
                }
                if (!accessible(variants.first())) {
                    errors += "${client.name} : accès au fichier refusé"
                    break
                }
                val title = details?.optString("title")?.takeIf { it.isNotBlank() }
                return listOf(Resolved(id, title, variants))
            }
        }
        val all = errors.joinToString("\n")
        err(
            when {
                listOf("bot", "robot", "Sign in", "Connectez-vous").any { all.contains(it, ignoreCase = true) } ->
                    "YouTube demande de confirmer que tu n'es pas un robot : réessaie plus tard ou depuis un autre réseau (Wi-Fi / 4G)"
                listOf("age", "âge").any { all.contains(it, ignoreCase = true) } ->
                    "Vidéo YouTube soumise à une limite d'âge (connexion requise) : non prise en charge"
                listOf("private", "privée", "unavailable", "indisponible").any { all.contains(it, ignoreCase = true) } ->
                    "Vidéo YouTube privée ou indisponible"
                else -> "YouTube a refusé le téléchargement :\n$all"
            }
        )
    }

    private fun player(c: Client, id: String, visitorData: String?): JSONObject {
        val client = JSONObject()
            .put("clientName", c.name)
            .put("clientVersion", c.version)
            .put("userAgent", c.userAgent)
            .put("hl", "fr")
            .put("gl", "FR")
        c.extra.forEach { (k, v) -> client.put(k, v) }
        visitorData?.let { client.put("visitorData", it) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put(
                "playbackContext",
                JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS")),
            )
        val headers = HashMap<String, String>()
        headers["User-Agent"] = c.userAgent
        headers["X-YouTube-Client-Name"] = c.id.toString()
        headers["X-YouTube-Client-Version"] = c.version
        headers["Origin"] = "https://www.youtube.com"
        visitorData?.let { headers["X-Goog-Visitor-Id"] = it }
        val (code, text) = Http.postJson(PLAYER_URL, body.toString(), headers)
        if (code !in 200..299) throw RedgifsApi.HttpError(code, "HTTP $code")
        return JSONObject(text)
    }

    private class Fmt(val url: String, val mime: String, val height: Int?, val bitrate: Long, val size: Long?, val defaultAudio: Boolean)

    private fun formats(arr: JSONArray?): List<Fmt> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val f = arr.optJSONObject(i) ?: return@mapNotNull null
            // Pas d'URL directe = signature chiffrée (non géré) : format ignoré
            val u = f.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            if (f.optBoolean("isDrc")) return@mapNotNull null
            val w = f.optInt("width", 0)
            val h = f.optInt("height", 0)
            Fmt(
                u,
                f.optString("mimeType"),
                if (w > 0 && h > 0) minOf(w, h) else h.takeIf { it > 0 },
                f.optLong("bitrate", 0),
                f.optString("contentLength").toLongOrNull(),
                f.optJSONObject("audioTrack")?.optBoolean("audioIsDefault", false) ?: true,
            )
        }
    }

    /** Formats retenus pour une réponse "streamingData" (avec la 1re appli de la liste). */
    internal fun variantsFor(sd: JSONObject): List<Variant> = variantsFrom(sd, CLIENTS.first())

    private fun variantsFrom(sd: JSONObject, client: Client): List<Variant> {
        val headers = mapOf("User-Agent" to client.userAgent)
        val result = ArrayList<Variant>()

        val adaptive = formats(sd.optJSONArray("adaptiveFormats"))
        val audio = adaptive.filter { it.mime.startsWith("audio/mp4") }
            .sortedWith(compareByDescending<Fmt> { it.defaultAudio }.thenByDescending { it.bitrate })
            .firstOrNull()
        if (audio != null) {
            // H.264 uniquement : réassemblage MP4 et lecture fiables sur tous les téléphones
            adaptive.filter { it.mime.startsWith("video/mp4") && it.mime.contains("avc1") && it.height != null }
                .groupBy { it.height }
                .mapNotNull { (_, list) -> list.maxByOrNull { it.bitrate } }
                .forEach { v -> result += Variant(v.height, Plan.Split(v.url, audio.url, headers, v.size, audio.size)) }
        }
        // MP4 tout-en-un (généralement 360p)
        formats(sd.optJSONArray("formats")).filter { it.mime.startsWith("video/mp4") }
            .forEach { result += Variant(it.height, Plan.Direct(it.url, headers)) }
        // HLS (appli iOS)
        sd.optString("hlsManifestUrl").takeIf { it.startsWith("http") }?.let { hls ->
            runCatching { result += Extractors.hlsVariants(hls, headers) }
        }
        // À hauteur égale : vidéo+son séparés (meilleur débit) avant tout-en-un, puis HLS
        return result.sortedWith(
            compareByDescending<Variant> { it.height ?: -1 }.thenBy {
                when (it.plan) {
                    is Plan.Split -> 0
                    is Plan.Direct -> 1
                    else -> 2
                }
            }
        )
    }

    /** Teste la variante principale (les adresses peuvent exiger un jeton que ce client n'a pas). */
    private fun accessible(v: Variant): Boolean = when (val p = v.plan) {
        is Plan.Split -> Http.probe(p.videoUrl, p.headers) && (p.audioUrl == null || Http.probe(p.audioUrl, p.headers))
        is Plan.Direct -> Http.probe(p.url, p.headers)
        else -> true
    }
}
