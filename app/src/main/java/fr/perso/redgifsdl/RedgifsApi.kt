package fr.perso.redgifsdl

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client minimal de l'API RedGIFs v2, même logique que github.com/scrazzz/redgifs :
 *  1. GET /v2/auth/temporary            -> "token"
 *  2. GET /v2/gifs/{id} (Bearer token)  -> gif.urls.hd (sinon gif.urls.sd)
 *  3. GET du MP4
 * Collections (non gérées par la bibliothèque, mêmes endpoints que le site) :
 *  GET /v2/users/{user}/collections/{id}                  -> folderName, contentCount
 *  GET /v2/users/{user}/collections/{id}/gifs?page=&count= -> gifs[] (count <= 100)
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
    private const val PAGE_SIZE = 100 // maximum accepté par l'API ("wrong page size" au-delà)

    open class ApiException(message: String) : IOException(message)
    class HttpError(val code: Int, message: String) : ApiException(message)

    /** Ce que désigne le lien collé / partagé. */
    sealed class Target {
        data class Single(val id: String) : Target()
        data class Collection(val user: String, val id: String) : Target()
    }

    data class Media(val id: String, val url: String, val isHd: Boolean) {
        /** Nom de fichier d'origine, ex. "RedPossibleYak.mp4". */
        val fileName: String
            get() = url.substringAfterLast('/').substringBefore('?')
                .takeIf { it.contains('.') } ?: "$id.mp4"

        val mimeType: String
            get() = when (fileName.substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "video/mp4"
            }
    }

    data class CollectionInfo(val name: String, val count: Int)

    /**
     * Réessaie [block] sur erreur réseau passagère (coupure, délai dépassé, erreur serveur 5xx),
     * avec une pause croissante. Les erreurs définitives de l'API (404, 401…) ne sont pas réessayées.
     */
    fun <T> retrying(attempts: Int = 3, block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                val transient = e !is ApiException || (e is HttpError && e.code >= 500)
                if (!transient || attempt >= attempts) throw e
            }
            Thread.sleep(3000L * attempt)
            attempt++
        }
    }

    private val COLLECTION_RE =
        Regex("""redgifs\.com/users/([^/?#\s]+)/collections/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    private val ID_PATTERNS = listOf(
        // https://www.redgifs.com/watch/xxxx, /ifr/xxxx, /i/xxxx (v3., www., sans sous-domaine…)
        Regex("""redgifs\.com/(?:watch|ifr|i)/([A-Za-z]+)""", RegexOption.IGNORE_CASE),
        // https://media.redgifs.com/Xxxx.mp4, https://thumbs2.redgifs.com/Xxxx-mobile.mp4
        Regex("""(?:media|thumbs\d*)\.redgifs\.com/([A-Za-z]+)""", RegexOption.IGNORE_CASE),
    )

    /** Analyse un lien RedGIFs (ou un texte partagé qui en contient un). */
    fun parse(text: String): Target? {
        COLLECTION_RE.find(text)?.let { return Target.Collection(it.groupValues[1], it.groupValues[2]) }
        for (re in ID_PATTERNS) {
            re.find(text)?.let { return Target.Single(it.groupValues[1].lowercase()) }
        }
        // Identifiant seul collé dans le champ
        val trimmed = text.trim()
        if (trimmed.matches(Regex("[A-Za-z]{6,}"))) return Target.Single(trimmed.lowercase())
        return null
    }

    private fun open(url: String, token: String?): HttpURLConnection {
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

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun mediaFrom(gif: JSONObject, preferHd: Boolean = true): Media? {
        val id = gif.optString("id")
        val urls = gif.optJSONObject("urls") ?: return null
        val hd = urls.optString("hd").takeIf { it.startsWith("http") }
        val sd = urls.optString("sd").takeIf { it.startsWith("http") }
        return when {
            preferHd && hd != null -> Media(id, hd, true)
            sd != null -> Media(id, sd, false)
            hd != null -> Media(id, hd, true)
            else -> null
        }
    }

    /** Une session = un jeton temporaire, réutilisé pour toutes les requêtes et renouvelé sur 401. */
    /** @param preferHd false = version SD (≈ 480p ou moins), selon la qualité choisie */
    class Session(private val preferHd: Boolean = true) {
        private var token: String? = null

        private fun fetchTemporaryToken(): String {
            val c = open("$API/v2/auth/temporary", null)
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

        private fun currentToken(): String = token ?: fetchTemporaryToken().also { token = it }

        private fun getJson(path: String): JSONObject = retrying { getJsonOnce(path) }

        private fun getJsonOnce(path: String): JSONObject {
            for (attempt in 0..1) {
                val c = open(API + path, currentToken())
                try {
                    val code = c.responseCode
                    val body = readBody(c)
                    if (code == 200) return JSONObject(body)
                    if (code == 401 && attempt == 0) {
                        token = null // jeton expiré ou IP changée : on en redemande un
                        continue
                    }
                    throw HttpError(code, "Erreur API HTTP $code" + (errorMessage(body)?.let { " : $it" } ?: ""))
                } finally {
                    c.disconnect()
                }
            }
            throw ApiException("Authentification refusée par RedGIFs")
        }

        fun gif(id: String): Media {
            val json = try {
                getJson("/v2/gifs/${enc(id)}")
            } catch (e: HttpError) {
                if (e.code == 404 || e.code == 410) throw ApiException("Vidéo introuvable ou supprimée (« $id »)")
                throw e
            }
            return mediaFrom(json.getJSONObject("gif"), preferHd)
                ?: throw ApiException("Aucune URL vidéo dans la réponse")
        }

        fun collectionInfo(user: String, id: String): CollectionInfo {
            val json = try {
                getJson("/v2/users/${enc(user)}/collections/${enc(id)}")
            } catch (e: HttpError) {
                if (e.code == 404 || e.code == 403) throw ApiException("Collection introuvable ou privée")
                throw e
            }
            val name = json.optString("folderName").takeIf { it.isNotBlank() && it != "null" } ?: id
            return CollectionInfo(name, json.optInt("contentCount", -1))
        }

        /** Toutes les vidéos de la collection (toutes les pages). */
        fun collectionItems(user: String, id: String): List<Media> {
            val result = ArrayList<Media>()
            val seen = HashSet<String>()
            var page = 1
            var pages: Int
            do {
                val json = getJson("/v2/users/${enc(user)}/collections/${enc(id)}/gifs?page=$page&count=$PAGE_SIZE")
                pages = json.optInt("pages", 1)
                val gifs = json.optJSONArray("gifs") ?: break
                for (i in 0 until gifs.length()) {
                    val m = mediaFrom(gifs.getJSONObject(i), preferHd) ?: continue
                    if (seen.add(m.id.lowercase())) result += m
                }
                page++
            } while (page <= pages && gifs.length() > 0)
            return result
        }

        /** Ouvre le flux du média avec les mêmes en-têtes que l'API (comme la bibliothèque Python). */
        fun openStream(media: Media): HttpURLConnection {
            val c = open(media.url, currentToken())
            val code = c.responseCode
            if (code !in 200..299) {
                c.disconnect()
                throw HttpError(code, "Téléchargement refusé (HTTP $code)")
            }
            return c
        }
    }
}
