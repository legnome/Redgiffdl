package fr.perso.redgifsdl

import android.content.Context
import java.util.Locale

/** Enchaîne API + enregistrement dans la bibliothèque privée. À appeler depuis un thread d'arrière-plan. */
class Downloader(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onStatus(text: String)
        /** fraction entre 0 et 1, ou null pour une progression indéterminée */
        fun onProgress(fraction: Float?)
        /** début du fichier n° index (0..total-1) */
        fun onItem(index: Int, total: Int) {}
    }

    /** [short] : résumé d'une ligne, utilisé quand plusieurs collections sont mises à jour d'affilée. */
    class Outcome(val message: String, val success: Boolean, val short: String = message)

    private val api = RedgifsApi.Session()

    fun run(target: RedgifsApi.Target): Outcome = when (target) {
        is RedgifsApi.Target.Single -> downloadSingle(target.id)
        is RedgifsApi.Target.Collection -> downloadCollection(target.user, target.id)
    }

    private fun downloadSingle(id: String): Outcome {
        listener.onStatus("Connexion à RedGIFs…")
        listener.onProgress(null)
        val folder = Library.folderFor(null)
        if (id in Library.existingKeys(context, folder)) {
            return Outcome("Déjà dans la bibliothèque (${Library.label(folder)}) : rien à faire.", true)
        }
        val media = api.gif(id)
        listener.onItem(0, 1)
        val file = downloadOne(media, folder) { written, total ->
            listener.onStatus("Téléchargement de ${media.fileName} (${quality(media)})… ${sizes(written, total)}")
            listener.onProgress(fraction(written, total))
        }
        val note = if (media.isHd) " (HD)" else " (SD, pas de HD dispo)"
        return Outcome("Vidéo ajoutée à la bibliothèque$note :\n${Library.label(folder)} › ${file.name}", true)
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
        val existing = Library.existingKeys(context, folder)
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
        var hdMissing = 0
        todo.forEachIndexed { index, media ->
            val label = "$header\n\n${index + 1}/${todo.size} : ${media.fileName} (${quality(media)})"
            listener.onStatus(label)
            listener.onItem(index, todo.size)
            try {
                downloadOne(media, folder) { written, total ->
                    listener.onStatus("$label\n${sizes(written, total)}")
                    val f = fraction(written, total) ?: 0f
                    listener.onProgress((index + f) / todo.size)
                }
                if (!media.isHd) hdMissing++
            } catch (e: Exception) {
                failed += "${media.id} : ${describe(e)}"
            }
            listener.onProgress((index + 1f) / todo.size)
        }

        val ok = todo.size - failed.size
        val sb = StringBuilder()
        sb.append("Collection « ").append(info.name).append(" » terminée\n")
        sb.append("• ").append(ok).append(" téléchargée(s)")
        if (hdMissing > 0) sb.append(" (dont ").append(hdMissing).append(" en SD)")
        sb.append("\n• ").append(already.size).append(" déjà présente(s), ignorée(s)")
        if (skipped.isNotEmpty()) {
            sb.append("\n• ").append(skipped.size).append(" exclue(s) (supprimées ou déplacées par toi)")
        }
        if (failed.isNotEmpty()) {
            sb.append("\n• ").append(failed.size).append(" en échec :")
            failed.forEach { sb.append("\n   - ").append(it) }
        }
        sb.append("\n\nBibliothèque › ").append(Library.label(folder))
        val short = "« ${info.name} » : $ok nouvelle(s)" + (if (failed.isNotEmpty()) ", ${failed.size} en échec" else "")
        return Outcome(sb.toString(), failed.isEmpty(), short)
    }

    private fun downloadOne(
        media: RedgifsApi.Media,
        folder: String,
        onProgress: (Long, Long) -> Unit,
    ): java.io.File = RedgifsApi.retrying {
        // En cas d'échec, Library.save supprime le fichier partiel : chaque tentative repart de zéro.
        val conn = api.openStream(media)
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                Library.save(context, folder, media.fileName, input, total, onProgress)
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun describe(e: Exception): String = when (e) {
            is RedgifsApi.ApiException -> e.message ?: "Erreur API"
            is java.net.UnknownHostException -> "Pas de connexion Internet"
            is java.net.SocketTimeoutException -> "Délai dépassé, réessaie"
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
