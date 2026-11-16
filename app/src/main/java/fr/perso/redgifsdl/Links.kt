package fr.perso.redgifsdl

import java.net.URI

/** Reconnaissance des liens collés ou partagés. */
object Links {

    sealed class Target {
        data class RedgifsSingle(val id: String) : Target()
        data class RedgifsCollection(val user: String, val id: String) : Target()
        /** Page d'un autre site, résolue par l'extracteur correspondant. */
        data class Page(val site: Site, val url: String) : Target()
    }

    private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** Toutes les URL présentes dans un texte (ponctuation finale retirée). */
    fun urlsIn(text: String): List<String> =
        URL_RE.findAll(text).map { it.value.trimEnd('.', ',', ';', ')', ']', '!', '?') }.distinct().toList()

    /**
     * Découpe un texte (un ou plusieurs liens) en liens reconnus.
     * @return paires (lien, cible) ; les liens non reconnus sont renvoyés dans [unknown].
     */
    fun parseAll(text: String, unknown: MutableList<String>? = null): List<Pair<String, Target>> {
        val urls = urlsIn(text)
        if (urls.isEmpty()) {
            // Identifiant RedGIFs seul
            return parse(text)?.let { listOf(text.trim() to it) } ?: run {
                if (text.isNotBlank()) unknown?.add(text.trim())
                emptyList()
            }
        }
        val result = ArrayList<Pair<String, Target>>()
        for (u in urls) {
            val t = parse(u)
            if (t != null) result += u to t else unknown?.add(u)
        }
        return result
    }

    /** Reconnaît UN lien. */
    fun parse(text: String): Target? {
        RedgifsApi.parse(text)?.let {
            return when (it) {
                is RedgifsApi.Target.Single -> Target.RedgifsSingle(it.id)
                is RedgifsApi.Target.Collection -> Target.RedgifsCollection(it.user, it.id)
            }
        }
        val url = urlsIn(text).firstOrNull() ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.path ?: ""
        val site = when {
            host.contains("xvideos") && path.contains("/video") -> Site.XVIDEOS
            host.contains("xnxx") && path.contains("/video") -> Site.XNXX
            host.contains("pornhub") && (uri.query ?: "").contains("viewkey=") -> Site.PORNHUB
            isTwitterHost(host) && TWEET_ID.containsMatchIn(path) -> Site.TWITTER
            isRedditHost(host) -> Site.REDDIT
            youtubeId(url) != null -> Site.YOUTUBE
            else -> return null
        }
        return Target.Page(site, url)
    }

    private val YT_HOSTS = setOf("youtube.com", "music.youtube.com", "youtube-nocookie.com", "youtu.be")
    private val YT_PATH_ID = Regex("""^/(?:shorts|live|embed|v|e)/([A-Za-z0-9_-]{11})""")
    private val YT_QUERY_ID = Regex("""(?:^|&)v=([A-Za-z0-9_-]{11})""")

    /** Identifiant (11 caractères) d'un lien YouTube : watch?v=, youtu.be/, /shorts/, /live/, /embed/. */
    fun youtubeId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        if (host !in YT_HOSTS) return null
        val path = uri.path ?: ""
        if (host == "youtu.be") return Regex("""^/([A-Za-z0-9_-]{11})""").find(path)?.groupValues?.get(1)
        YT_PATH_ID.find(path)?.let { return it.groupValues[1] }
        if (path == "/watch" || path.isEmpty() || path == "/") {
            return YT_QUERY_ID.find(uri.rawQuery ?: "")?.groupValues?.get(1)
        }
        return null
    }

    val TWEET_ID = Regex("""/status(?:es)?/(\d+)""")

    private fun isTwitterHost(h: String) = h in setOf(
        "twitter.com", "x.com", "mobile.twitter.com", "mobile.x.com",
        "fxtwitter.com", "vxtwitter.com", "fixupx.com", "fixvx.com", "twittpr.com",
    )

    private fun isRedditHost(h: String) =
        h == "reddit.com" || h.endsWith(".reddit.com") || h == "redd.it" || h == "v.redd.it"
}
