package fr.perso.redgifsdl

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.UUID

/** Enchaîne extraction + téléchargement + enregistrement dans la bibliothèque privée. Thread d'arrière-plan. */
class Downloader(
    private val context: Context,
    private val listener: Listener,
    private val quality: Quality = Quality.MAX,
) {

    interface Listener {
        fun onStatus(text: String)
        /** fraction entre 0 et 1, ou null pour une progression indéterminée */
        fun onProgress(fraction: Float?)
        /** début du fichier n° index (0..total-1) */
        fun onItem(index: Int, total: Int) {}
    }

    /** [short] : résumé d'une ligne, utilisé quand plusieurs liens sont traités d'affilée. */
    class Outcome(val message: String, val success: Boolean, val short: String = message)

    // RedGIFs n'a que 2 qualités : HD (souvent 1080p) et SD (≈ 480p)
    private val api = RedgifsApi.Session(preferHd = quality.maxHeight > 480)

    fun run(target: Links.Target): Outcome = when (target) {
        is Links.Target.RedgifsSingle -> downloadRedgifsSingle(target.id)
        is Links.Target.RedgifsCollection -> downloadCollection(target.user, target.id)
        is Links.Target.Page -> downloadPage(target.site, target.url)
    }

    // ------------------------------------------------------------ RedGIFs

    private fun downloadRedgifsSingle(id: String): Outcome {
        listener.onStatus("Connexion à RedGIFs…")
        listener.onProgress(null)
        val folder = Library.folderFor(null)
        if (id in Library.existingKeys(context, Site.REDGIFS, folder)) {
            return Outcome("Déjà dans la bibliothèque (RedGIFs) : rien à faire.", true, "RedGIFs $id : déjà là")
        }
        val media = api.gif(id)
        listener.onItem(0, 1)
        val file = downloadRedgifs(media, folder) { written, total ->
            listener.onStatus("Téléchargement de ${media.fileName} (${quality(media)})… ${sizes(written, total)}")
            listener.onProgress(fraction(written, total))
        }
        val note = if (media.isHd) " (HD)" else " (SD)"
        return Outcome(
            "Vidéo ajoutée à la bibliothèque$note :\nRedGIFs › ${Library.label(folder)} › ${file.name}",
            true, "RedGIFs : ${file.name}",
        )
    }

    private fun downloadCollection(user: String, collectionId: String): Outcome {
        listener.onStatus("Lecture de la collection…")
        listener.onProgress(null)
        val info = api.collectionInfo(user, collectionId)

        // Collection déjà connue : on garde son dossier d'origine (même si elle a été renommée).
        val saved = CollectionStore.get(context, user, collectionId)
        val folder = saved?.folder ?: run {
            val base = Library.folderFor(info.name)
            // Deux collections différentes portant le même nom : dossiers distincts
            val other = CollectionStore.forFolder(context, base)
            if (other != null) "$base ($collectionId)" else base
        }
        CollectionStore.remember(context, user, collectionId, info.name, folder)

        val items = api.collectionItems(user, collectionId)
        if (items.isEmpty()) {
            return Outcome("La collection « ${info.name} » est vide.", true, "« ${info.name} » : vide")
        }

        // Vérification AVANT téléchargement : ce qui est déjà dans le dossier est ignoré,
        // ainsi que les vidéos que tu as supprimées ou déplacées dans la galerie.
        val existing = Library.existingKeys(context, Site.REDGIFS, folder)
        val excluded = saved?.excluded ?: emptySet()
        val already = items.filter { it.id.lowercase() in existing }
        val skipped = items.filter { it.id.lowercase() !in existing && it.id.lowercase() in excluded }
        val todo = items.filter { it.id.lowercase() !in existing && it.id.lowercase() !in excluded }

        val header = "Collection « ${info.name} » : ${items.size} vidéo(s), " +
            "${already.size} déjà téléchargée(s)" +
            (if (skipped.isNotEmpty()) ", ${skipped.size} exclue(s)" else "") +
            ", ${todo.size} à télécharger"
        if (todo.isEmpty()) {
            return Outcome("$header.\nTout est déjà dans la bibliothèque.", true, "« ${info.name} » : à jour")
        }

        val failed = ArrayList<String>()
        var sdCount = 0
        todo.forEachIndexed { index, media ->
            val label = "$header\n\n${index + 1}/${todo.size} : ${media.fileName} (${quality(media)})"
            listener.onStatus(label)
            listener.onItem(index, todo.size)
            try {
                downloadRedgifs(media, folder) { written, total ->
                    listener.onStatus("$label\n${sizes(written, total)}")
                    listener.onProgress((index + (fraction(written, total) ?: 0f)) / todo.size)
                }
                if (!media.isHd) sdCount++
            } catch (e: Exception) {
                failed += "${media.id} : ${describe(e)}"
            }
            listener.onProgress((index + 1f) / todo.size)
        }

        val ok = todo.size - failed.size
        val sb = StringBuilder()
        sb.append("Collection « ").append(info.name).append(" » terminée\n")
        sb.append("• ").append(ok).append(" téléchargée(s)")
        if (sdCount > 0) sb.append(" (dont ").append(sdCount).append(" en SD)")
        sb.append("\n• ").append(already.size).append(" déjà présente(s), ignorée(s)")
        if (skipped.isNotEmpty()) {
            sb.append("\n• ").append(skipped.size).append(" exclue(s) (supprimées ou déplacées par toi)")
        }
        appendFailures(sb, failed)
        sb.append("\n\nBibliothèque › RedGIFs › ").append(Library.label(folder))
        val short = "« ${info.name} » : $ok nouvelle(s)" + (if (failed.isNotEmpty()) ", ${failed.size} en échec" else "")
        return Outcome(sb.toString(), failed.isEmpty(), short)
    }

    private fun downloadRedgifs(
        media: RedgifsApi.Media,
        folder: String,
        onProgress: (Long, Long) -> Unit,
    ): File = RedgifsApi.retrying {
        // En cas d'échec, Library.save supprime le fichier partiel : chaque tentative repart de zéro.
        val conn = api.openStream(media)
        try {
            conn.inputStream.use { input ->
                Library.save(context, Site.REDGIFS, folder, media.fileName, input, conn.contentLengthLong, onProgress)
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ Autres sites

    private fun downloadPage(site: Site, url: String): Outcome {
        listener.onStatus("Analyse de la page ${site.label}…")
        listener.onProgress(null)
        val items = RedgifsApi.retrying { Extractors.forSite(site).resolve(url) }

        val existing = Library.existingKeys(context, site, "")
        val already = items.filter { it.id.lowercase() in existing }
        val todo = items.filter { it.id.lowercase() !in existing }
        if (todo.isEmpty()) {
            return Outcome("Déjà dans la bibliothèque (${site.label}) : rien à faire.", true, "${site.label} : déjà là")
        }

        val saved = ArrayList<Pair<File, Int?>>()
        val failed = ArrayList<String>()
        todo.forEachIndexed { index, item ->
            val name = item.title?.take(50) ?: item.id
            val label = (if (todo.size > 1) "${site.label} : ${index + 1}/${todo.size}\n" else "${site.label}\n") + name
            listener.onStatus(label)
            listener.onItem(index, todo.size)
            try {
                saved += fetch(site, item) { f, detail ->
                    listener.onStatus("$label\n$detail")
                    listener.onProgress(f?.let { (index + it) / todo.size })
                }
            } catch (e: Exception) {
                if (todo.size == 1) throw e
                failed += "$name : ${describe(e)}"
            }
        }

        if (todo.size == 1) {
            val (file, height) = saved.single()
            val q = height?.let { " (${it}p)" } ?: ""
            return Outcome(
                "Vidéo ajoutée à la bibliothèque$q :\n${site.label} › ${file.name}",
                true, "${site.label} : ${file.name}",
            )
        }
        val sb = StringBuilder("${site.label} : ${saved.size} fichier(s) téléchargé(s)")
        if (already.isNotEmpty()) sb.append("\n• ").append(already.size).append(" déjà présent(s), ignoré(s)")
        appendFailures(sb, failed)
        return Outcome(sb.toString(), failed.isEmpty(), "${site.label} : ${saved.size} fichier(s)")
    }

    /** Télécharge un élément selon son plan. @return le fichier et la hauteur choisie (si connue). */
    private fun fetch(site: Site, item: Resolved, report: (Float?, String) -> Unit): Pair<File, Int?> {
        val tried = ArrayList<String>()
        val candidates = candidates(item.variants).take(MAX_CANDIDATES)
        for ((i, variant) in candidates.withIndex()) {
            try {
                return fetchVariant(site, item, variant, report) to variant.height
            } catch (e: RedgifsApi.HttpError) {
                // Format refusé (ex. YouTube : jeton exigé, adresse expirée) : on essaie le suivant
                if (e.code !in REFUSED || i == candidates.lastIndex) {
                    if (tried.isEmpty()) throw e
                    tried += label(variant) + " : HTTP ${e.code}"
                    throw RedgifsApi.ApiException("Tous les formats ont été refusés :\n" + tried.joinToString("\n"))
                }
                tried += label(variant) + " : HTTP ${e.code}"
                report(null, "Format refusé (HTTP ${e.code}), essai d'un autre…")
            }
        }
        throw RedgifsApi.ApiException("Aucun format téléchargeable")
    }

    /** Ordre d'essai : le format choisi, puis les autres ≤ qualité demandée (meilleur d'abord), puis au-dessus. */
    private fun candidates(variants: List<Variant>): List<Variant> {
        val first = Extractors.pick(variants, quality)
        val rest = variants.filter { it !== first }
        val within = rest.filter { (it.height ?: 0) <= quality.maxHeight }.sortedByDescending { it.height ?: -1 }
        val above = rest.filter { (it.height ?: 0) > quality.maxHeight }.sortedBy { it.height }
        return listOf(first) + within + above
    }

    private fun label(v: Variant): String {
        val kind = when (v.plan) {
            is Plan.Hls -> "HLS"
            is Plan.Split -> "vidéo+son"
            else -> "MP4"
        }
        return listOfNotNull(v.source, v.height?.let { "${it}p" }, kind).joinToString(" ")
    }

    /** Télécharge une variante selon son plan. */
    private fun fetchVariant(site: Site, item: Resolved, variant: Variant, report: (Float?, String) -> Unit): File {
        val q = variant.height?.let { "${it}p" } ?: "qualité d'origine"
        val tmpDir = File(context.cacheDir, "dl").apply { mkdirs() }
        fun tmp(ext: String) = File(tmpDir, UUID.randomUUID().toString() + "." + ext)

        val file = when (val plan = variant.plan) {
            is Plan.Direct -> RedgifsApi.retrying {
                val conn = Http.openOk(plan.url, plan.headers)
                try {
                    val name = Library.fileNameFor(item.title, item.id, plan.ext)
                    conn.inputStream.use { input ->
                        Library.save(context, site, "", name, input, conn.contentLengthLong) { w, t ->
                            report(fraction(w, t), "$q · ${sizes(w, t)}")
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }

            is Plan.Redgifs -> {
                val media = api.gif(plan.id)
                val ext = media.fileName.substringAfterLast('.', "mp4")
                RedgifsApi.retrying {
                    val conn = api.openStream(media)
                    try {
                        conn.inputStream.use { input ->
                            val name = Library.fileNameFor(item.title, item.id, ext)
                            Library.save(context, site, "", name, input, conn.contentLengthLong) { w, t ->
                                report(fraction(w, t), "${quality(media)} · ${sizes(w, t)}")
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            }

            is Plan.Hls -> {
                val video = tmp("ts")
                val audio = plan.audioUrl?.let { tmp("ts") }
                val out = tmp("mp4")
                try {
                    val videoShare = if (audio != null) 0.75f else 0.95f
                    val fmp4 = Hls.download(plan.url, plan.headers, video) { d, t ->
                        report(videoShare * d / t, "$q · segment $d/$t")
                    }
                    if (audio != null) {
                        Hls.download(plan.audioUrl, plan.headers, audio) { d, t ->
                            report(videoShare + 0.2f * d / t, "$q · audio $d/$t")
                        }
                    }
                    report(null, "$q · assemblage…")
                    val name = Library.fileNameFor(item.title, item.id, "mp4")
                    if (fmp4 && audio == null) {
                        Library.importFile(context, site, "", name, video)
                    } else {
                        try {
                            Remux.mux(listOfNotNull(video, audio), out)
                            Library.importFile(context, site, "", name, out)
                        } catch (e: Exception) {
                            // Assemblage impossible : on garde le flux brut (lisible tel quel par le lecteur)
                            val ext = if (fmp4) "mp4" else "ts"
                            Library.importFile(context, site, "", Library.fileNameFor(item.title, item.id, ext), video)
                        }
                    }
                } finally {
                    video.delete()
                    audio?.delete()
                    out.delete()
                }
            }

            is Plan.Split -> {
                val video = tmp("mp4")
                val audio = tmp("m4a")
                val out = tmp("mp4")
                try {
                    val onVideo = { w: Long, t: Long -> report((fraction(w, t) ?: 0f) * 0.75f, "$q · vidéo ${sizes(w, t)}") }
                    val videoSize = plan.videoSize
                    if (videoSize != null && videoSize > 0) {
                        Http.downloadChunked(plan.videoUrl, plan.headers, video, videoSize, onProgress = onVideo)
                    } else {
                        RedgifsApi.retrying { Http.download(plan.videoUrl, plan.headers, video, onVideo) }
                    }
                    val audioUrl = plan.audioUrl
                    val hasAudio = audioUrl != null && try {
                        val onAudio = { w: Long, t: Long -> report(0.75f + (fraction(w, t) ?: 0f) * 0.2f, "$q · son ${sizes(w, t)}") }
                        val audioSize = plan.audioSize
                        if (audioSize != null && audioSize > 0) {
                            Http.downloadChunked(audioUrl, plan.headers, audio, audioSize, onProgress = onAudio)
                        } else {
                            RedgifsApi.retrying { Http.download(audioUrl, plan.headers, audio, onAudio) }
                        }
                        true
                    } catch (e: RedgifsApi.HttpError) {
                        // Son refusé (ex. YouTube 403) : pas de vidéo muette, on passe au format suivant
                        if (e.code in REFUSED) throw e
                        false
                    } catch (e: java.io.IOException) {
                        false // ex. Reddit : piste son absente à l'origine
                    }
                    report(null, "$q · assemblage…")
                    val name = Library.fileNameFor(item.title, item.id, "mp4")
                    if (!hasAudio) {
                        Library.importFile(context, site, "", name, video) // vidéo sans piste son à l'origine
                    } else {
                        try {
                            Remux.mux(listOf(video, audio), out)
                            Library.importFile(context, site, "", name, out)
                        } catch (e: Exception) {
                            Library.importFile(context, site, "", name, video) // au pire : sans le son
                        }
                    }
                } finally {
                    video.delete()
                    audio.delete()
                    out.delete()
                }
            }
        }
        report(1f, q)
        return file
    }

    private fun appendFailures(sb: StringBuilder, failed: List<String>) {
        if (failed.isEmpty()) return
        sb.append("\n• ").append(failed.size).append(" en échec :")
        failed.forEach { sb.append("\n   - ").append(it) }
    }

    companion object {
        /** Codes HTTP qui font passer au format suivant. */
        private val REFUSED = setOf(401, 403, 404, 410)
        private const val MAX_CANDIDATES = 8

        fun describe(e: Exception): String = when (e) {
            is RedgifsApi.ApiException -> e.message ?: "Erreur API"
            is java.net.UnknownHostException -> "Pas de connexion Internet (ou site bloqué par ton opérateur)"
            is java.net.SocketTimeoutException -> "Délai dépassé, réessaie"
            is javax.net.ssl.SSLException -> "Connexion sécurisée refusée (site bloqué ? essaie avec un VPN)"
            else -> "${e.javaClass.simpleName} : ${e.message}"
        }

        private fun quality(m: RedgifsApi.Media) = if (m.isHd) "HD" else "SD"

        private fun fraction(written: Long, total: Long): Float? =
            if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else null

        private fun mb(bytes: Long) = String.format(Locale.FRANCE, "%.1f Mo", bytes / 1_048_576.0)

        private fun sizes(written: Long, total: Long) =
            if (total > 0) "${mb(written)} / ${mb(total)}" else mb(written)
    }
}
