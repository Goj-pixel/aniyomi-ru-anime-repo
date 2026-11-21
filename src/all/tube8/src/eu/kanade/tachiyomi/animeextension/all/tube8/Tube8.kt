package eu.kanade.tachiyomi.animeextension.all.tube8

import eu.kanade.tachiyomi.multisrc.xvideostheme.XvideosTheme

class Tube8 :
    XvideosTheme(
        name = "Tube8",
        baseUrl = "https://www.tube8.com",
        lang = "all",
    ) {
    override val popularPath = "/best"

    override val latestPath = "/newest"

    override val sections = listOf(
        "Популярное" to "/best",
        "Новое" to "/newest"
    )

    // Search pages are numbered from zero on the path tail.
    override fun searchUrl(query: String, page: Int): String = "$baseUrl/search/${query.trim().replace(' ', '+')}/${page - 1}"
}
