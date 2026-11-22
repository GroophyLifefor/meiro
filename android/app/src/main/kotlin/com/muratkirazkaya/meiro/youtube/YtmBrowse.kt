package com.muratkirazkaya.meiro.youtube

/**
 * A YouTube Music browse page (`youtube.BrowseResult`): the heading and the sections an album or an artist page holds.
 * [subtitle] ("Album • 2013"), [strapline] (the album's artist) and [secondSubtitle] ("13 songs • 1 hour, 14 minutes")
 * are read from the album page's `musicResponsiveHeaderRenderer`; an artist page's `musicImmersiveHeaderRenderer` has
 * only the title, the picture and the [description].
 */
data class BrowsePage(
    val title: String,
    val thumbnail: String,
    val subtitle: String = "",
    val strapline: String = "",
    val secondSubtitle: String = "",
    val description: String = "",
    val items: List<MusicItem> = emptyList(),
    val sections: List<Renderers.Section> = emptyList(),
    /** The page's square picture (the `microformat` one): an artist's [thumbnail] is a wide banner. */
    val square: String = "",
) {
    /** `tracksOf` (`collections.go:192`): every track of the page, in page order; one without a picture takes [art]. */
    fun tracks(art: String = ""): List<MusicItem> {
        val all = if (sections.isNotEmpty()) sections.flatMap { it.items } else items
        return all.filter { it.isTrack() }.map { if (it.thumbnail.isEmpty() && art.isNotEmpty()) it.copy(thumbnail = art) else it }
    }

    /** The section with a title (YouTube Music's English one, `hl=en`), or null. */
    fun section(title: String): Renderers.Section? = sections.firstOrNull { it.title.equals(title, ignoreCase = true) }
}

/** Reading browse responses (`youtube/browse.go` `browseResult`, `pageHeading`); pure, so the recorded fixtures test it. */
object YtmBrowse {
    fun parse(text: String): BrowsePage {
        val root = OrderedJson.parse(text)
        val parts = Renderers.browseParts(root, false)
        var title = ""; var thumb = ""; var description = ""
        // `pageHeading`: the first member of `header` that has a title (an artist's musicImmersiveHeaderRenderer).
        ((root as? JObj)?.obj("header"))?.members?.forEach { (_, v) ->
            val r = v as? JObj ?: return@forEach
            if (title.isEmpty()) {
                val t = Renderers.rendererText(r["title"])
                if (t.isNotEmpty()) {
                    title = t; thumb = Renderers.rendererThumbnail(r["thumbnail"])
                    description = Renderers.rendererText(r["description"]).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                }
            }
        }
        // An album page puts its heading among its contents (as a podcast show does, `GetPodcast`): the desktop takes
        // the album's name from the card that opened it, the phone reads it from the page.
        var subtitle = ""; var strapline = ""; var second = ""
        Renderers.findRenderer(root, "musicResponsiveHeaderRenderer")?.let { h ->
            if (title.isEmpty()) { title = Renderers.rendererText(h["title"]); thumb = Renderers.rendererThumbnail(h["thumbnail"]) }
            subtitle = Renderers.rendererText(h["subtitle"])
            strapline = Renderers.rendererText(h["straplineTextOne"])
            second = Renderers.rendererText(h["secondSubtitle"])
        }
        val square = (root as? JObj)?.obj("microformat")?.obj("microformatDataRenderer")?.let { Renderers.rendererThumbnail(it["thumbnail"]) }.orEmpty()
        return BrowsePage(title, thumb, subtitle, strapline, second, description, parts.items, parts.sections, square)
    }
}
