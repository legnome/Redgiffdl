package fr.perso.redgifsdl

/** Sites pris en charge. [label] = nom du dossier dans la bibliothèque. */
enum class Site(val label: String) {
    REDGIFS("RedGIFs"),
    XVIDEOS("XVideos"),
    XNXX("XNXX"),
    PORNHUB("Pornhub"),
    TWITTER("Twitter"),
    REDDIT("Reddit");

    companion object {
        fun fromLabel(label: String?): Site? = entries.firstOrNull { it.label == label }
    }
}

/** Qualité choisie par l'utilisateur : hauteur maximale (côté court de l'image). */
enum class Quality(val label: String, val maxHeight: Int) {
    MAX("Max", Int.MAX_VALUE),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360);

    companion object {
        fun fromName(name: String?): Quality = entries.firstOrNull { it.name == name } ?: MAX
    }
}
