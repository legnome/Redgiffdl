package fr.perso.redgifsdl

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client minimal de l'API RedGIFs v2, même logique que github.com/scrazzz/redgifs :
 *  1. GET /v2/auth/temporary            -> "token"
 *  2. GET /v2/gifs/{id} (Bearer token)  -> gif.urls.hd (sinon gif.urls.sd)
 *  3. GET du MP4
 *
 * Le jeton temporaire est lié à l'IP et au User-Agent qui l'ont demandé
 * (champs valid_addr / valid_agent du JWT) : on utilise donc toujours le même
 * User-Agent, et on redemande un jeton si l'API répond 401 (changement Wi-Fi/4G…).
 */
object RedgifsApi {

    private const val API = "https://api.redgifs.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 30_000

    class ApiException(message: String) : IOException(message)

    data class Video(val id: String, val url: String, val isHd: Boolean) {
        /** Nom de fichier d'origine, ex. "RedPossibleYak.mp4". */
        val fileName: String
            get() = url.substringAfterLast('/').substringBefore('?')
                .takeIf { it.endsWith(".mp4", ignoreCase = true) } ?: "$id.mp4"
    }

    private val ID_PATTERNS = listOf(
        // https://www.redgifs.com/watch/xxxx, /ifr/xxxx, /i/xxxx (v3., www., sans sous-domaine…)
        Regex("""redgifs\.com/(?:watch|ifr|i)/([A-Za-z]+)""", RegexOption.IGNORE_CASE),
        // https://media.redgifs.com/Xxxx.mp4, https://thumbs2.redgifs.com/Xxxx-mobile.mp4
        Regex("""(?:media|thumbs\d*)\.redgifs\.com/([A-Za-z]+)""", RegexOption.IGNORE_CASE),
    )

    /** Extrait l'identifiant (en minuscules) d'un lien RedGIFs ou d'un texte partagé qui en contient un. */
    fun extractId(text: String): String? {
        for (re in ID_PATTERNS) {
            re.find(text)?.let { return it.groupValues[1].lowercase() }
        }
        // Identifiant seul collé dans le champ
        val trimmed = text.trim()
        if (trimmed.matches(Regex("[A-Za-z]{6,}"))) return trimmed.lowercase()
        return null
    }

    private fun open(url: String, token: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept", "application/json, */*")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun readBody(c: HttpURLConnection): String {
        val stream: InputStream? = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun errorMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun fetchTemporaryToken(): String {
        val c = open("$API/v2/auth/temporary")
        try {
            val body = readBody(c)
            if (c.responseCode != 200) {
                throw ApiException("Impossible d'obtenir un jeton (HTTP ${c.responseCode})")
            }
            return JSONObject(body).optString("token").takeIf { it.isNotBlank() }
                ?: throw ApiException("Réponse d'authentification sans jeton")
        } finally {
            c.disconnect()
        }
    }

    /** Résout l'URL du MP4 (HD sinon SD). Renvoie aussi le jeton réellement utilisé. */
    fun resolveVideo(id: String): Pair<Video, String> {
        var token = fetchTemporaryToken()
        repeat(2) { attempt ->
            val c = open("$API/v2/gifs/$id", token)
            try {
                val code = c.responseCode
                val body = readBody(c)
                when {
                    code == 200 -> {
                        val urls = JSONObject(body).getJSONObject("gif").getJSONObject("urls")
                        val hd = urls.optString("hd").takeIf { it.startsWith("http") }
                        val sd = urls.optString("sd").takeIf { it.startsWith("http") }
                        val video = when {
                            hd != null -> Video(id, hd, true)
                            sd != null -> Video(id, sd, false)
                            else -> throw ApiException("Aucune URL vidéo dans la réponse")
                        }
                        return video to token
                    }
                    code == 401 && attempt == 0 -> token = fetchTemporaryToken() // jeton expiré / autre IP
                    code == 404 || code == 410 ->
                        throw ApiException("Vidéo introuvable ou supprimée (« $id »)")
                    else -> throw ApiException(
                        "Erreur API HTTP $code" + (errorMessage(body)?.let { " : $it" } ?: "")
                    )
                }
            } finally {
                c.disconnect()
            }
        }
        throw ApiException("Authentification refusée par RedGIFs")
    }

    /** Ouvre le flux du MP4 avec les mêmes en-têtes que l'API (comme la bibliothèque Python). */
    fun openVideoStream(video: Video, token: String): HttpURLConnection {
        val c = open(video.url, token)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw ApiException("Téléchargement du MP4 refusé (HTTP $code)")
        }
        return c
    }
}
