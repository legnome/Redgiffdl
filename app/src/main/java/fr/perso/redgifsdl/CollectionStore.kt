package fr.perso.redgifsdl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Collections enregistrées (fichier privé collections.json) : lien, nom, dossier de la bibliothèque,
 * et vidéos exclues (supprimées ou déplacées dans la galerie par l'utilisateur, à ne pas retélécharger).
 */
object CollectionStore {

    data class Saved(
        val user: String,
        val id: String,
        val name: String,
        val folder: String,
        val excluded: Set<String>,
        val lastCheck: Long,
    ) {
        val key get() = keyOf(user, id)
        val url get() = "https://www.redgifs.com/users/$user/collections/$id"
    }

    private val lock = Any()

    private fun keyOf(user: String, id: String) = "${user.lowercase()}/${id.lowercase()}"

    private fun file(context: Context) = File(context.filesDir, "collections.json")

    private fun load(context: Context): MutableMap<String, Saved> {
        val map = LinkedHashMap<String, Saved>()
        val f = file(context)
        if (!f.exists()) return map
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ex = o.optJSONArray("excluded")
                val excluded = HashSet<String>()
                if (ex != null) for (j in 0 until ex.length()) excluded += ex.getString(j)
                val s = Saved(
                    o.getString("user"), o.getString("id"), o.optString("name"),
                    o.getString("folder"), excluded, o.optLong("lastCheck"),
                )
                map[s.key] = s
            }
        }
        return map
    }

    private fun store(context: Context, map: Map<String, Saved>) {
        val arr = JSONArray()
        map.values.forEach { s ->
            arr.put(
                JSONObject()
                    .put("user", s.user).put("id", s.id).put("name", s.name)
                    .put("folder", s.folder).put("lastCheck", s.lastCheck)
                    .put("excluded", JSONArray(s.excluded.sorted()))
            )
        }
        // Écriture atomique : fichier temporaire puis renommage
        val tmp = File(context.filesDir, "collections.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(context))
    }

    fun all(context: Context): List<Saved> = synchronized(lock) {
        load(context).values.sortedBy { it.name.lowercase() }
    }

    fun get(context: Context, user: String, id: String): Saved? = synchronized(lock) {
        load(context)[keyOf(user, id)]
    }

    fun forFolder(context: Context, folder: String): Saved? = synchronized(lock) {
        load(context).values.firstOrNull { it.folder == folder }
    }

    /** Enregistre (ou met à jour) une collection après vérification. */
    fun remember(context: Context, user: String, id: String, name: String, folder: String) = synchronized(lock) {
        val map = load(context)
        val old = map[keyOf(user, id)]
        map[keyOf(user, id)] = Saved(user, id, name, folder, old?.excluded ?: emptySet(), System.currentTimeMillis())
        store(context, map)
    }

    /** L'utilisateur a supprimé / déplacé une vidéo d'un dossier de collection : ne plus la retélécharger. */
    fun exclude(context: Context, folder: String, videoKey: String) = synchronized(lock) {
        val map = load(context)
        val s = map.values.firstOrNull { it.folder == folder } ?: return@synchronized
        map[s.key] = s.copy(excluded = s.excluded + videoKey)
        store(context, map)
    }

    /** Dossier supprimé : on oublie aussi le lien de la collection. */
    fun forgetFolder(context: Context, folder: String) = synchronized(lock) {
        val map = load(context)
        if (map.values.removeAll { it.folder == folder }) store(context, map)
    }
}
