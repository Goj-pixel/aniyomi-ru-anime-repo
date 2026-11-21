package eu.kanade.tachiyomi.animeextension.all.thumbzilla

import eu.kanade.tachiyomi.multisrc.xvideostheme.XvideosTheme

class Thumbzilla :
    XvideosTheme(
        name = "Thumbzilla",
        baseUrl = "https://www.thumbzilla.com",
        lang = "all",
    ) {
    override val popularPath = "/best"

    override val latestPath = "/new"

    override val sections = listOf(
        "Популярное" to "/best",
        "Новое" to "/new",
    )

    // Search pages are numbered from zero on the path tail.
    override fun searchUrl(query: String, page: Int): String = "$baseUrl/search/${query.trim().replace(' ', '+')}/${page - 1}"
}
