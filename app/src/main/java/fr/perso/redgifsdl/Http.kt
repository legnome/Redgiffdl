package fr.perso.redgifsdl

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Petit client HTTP (HttpURLConnection) partagé par les extracteurs. */
object Http {

    /** User-Agent de navigateur de bureau (certains sites servent une page différente aux mobiles). */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private const val TIMEOUT_MS = 30_000

    fun open(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", DESKTOP_UA)
        c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    /** Ouvre et vérifie le code HTTP (erreur explicite sinon). */
    fun openOk(url: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val c = open(url, headers)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw RedgifsApi.HttpError(code, "HTTP $code sur ${hostOf(url)}")
        }
        return c
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = openOk(url, headers)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val c = openOk(url, headers)
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** URL finale après redirections (liens courts reddit, v.redd.it…). */
    fun finalUrl(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = open(url, headers)
        try {
            c.responseCode
            return c.url.toString()
        } finally {
            c.disconnect()
        }
    }

    /** true si l'URL répond 2xx (requête HEAD). */
    fun exists(url: String, headers: Map<String, String> = emptyMap()): Boolean = runCatching {
        val c = open(url, headers, "HEAD")
        try {
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    /** Télécharge dans [out] (écrase). */
    fun download(
        url: String,
        headers: Map<String, String>,
        out: File,
        onProgress: (written: Long, total: Long) -> Unit,
    ) {
        val c = openOk(url, headers)
        try {
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        if (written - last >= 256 * 1024) {
                            last = written
                            onProgress(written, total)
                        }
                    }
                    if (total > 0 && written < total) throw IOException("Téléchargement interrompu")
                    onProgress(written, total)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    /** POST d'un corps JSON, renvoie le texte de la réponse (même en cas d'erreur HTTP, avec le code). */
    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = open(url, headers + ("Content-Type" to "application/json"), "POST")
        try {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    /** Vérifie qu'une URL de média est vraiment accessible (lecture du premier octet). */
    fun probe(url: String, headers: Map<String, String> = emptyMap()): Boolean = runCatching {
        val c = open(url, headers + ("Range" to "bytes=0-0"))
        try {
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    /**
     * Téléchargement par morceaux (requêtes Range de [chunk] octets), chaque morceau réessayé
     * séparément : évite le bridage de débit de certains serveurs (YouTube) sur les longues requêtes.
     */
    fun downloadChunked(
        url: String,
        headers: Map<String, String>,
        out: File,
        total: Long,
        chunk: Long = 5L * 1024 * 1024,
        onProgress: (written: Long, total: Long) -> Unit,
    ) {
        FileOutputStream(out).use { output ->
            var pos = 0L
            while (pos < total) {
                val end = minOf(pos + chunk, total) - 1
                val bytes = RedgifsApi.retrying { getBytes(url, headers + ("Range" to "bytes=$pos-$end")) }
                if (bytes.isEmpty()) throw IOException("Réponse vide à l'octet $pos")
                output.write(bytes)
                pos += bytes.size
                onProgress(pos, total)
            }
        }
    }

    fun hostOf(url: String) = runCatching { URL(url).host }.getOrDefault(url)

    /** Résout une URL relative (playlists HLS, MPD…). */
    fun resolve(base: String, relative: String): String = URL(URL(base), relative.trim()).toString()

    /** Décode les entités HTML courantes (titres extraits des pages). */
    fun decodeEntities(s: String): String {
        var r = s
            .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
            .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        r = Regex("""&#(\d+);""").replace(r) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        r = Regex("""&#x([0-9a-fA-F]+);""").replace(r) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        return r.replace("&amp;", "&")
    }
}
