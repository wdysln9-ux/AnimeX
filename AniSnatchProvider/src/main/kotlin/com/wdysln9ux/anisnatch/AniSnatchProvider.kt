package com.wdysln9ux.anisnatch

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AniSnatchProvider : MainAPI() {
    override var mainUrl = "https://anisnatch.to"
    override var name = "AniSnatch"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Latest",
        "$mainUrl/recently-updated" to "Recently Updated",
        "$mainUrl/top-airing" to "Top Airing",
        "$mainUrl/most-popular" to "Popular"
    )

    private fun abs(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank() || s == "#") return null
        return when {
            s.startsWith("https://") || s.startsWith("http://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> mainUrl + s
            else -> "$mainUrl/$s"
        }
    }

    private fun Element.result(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = abs(a.attr("href")) ?: return null
        if (!href.contains(mainUrl) || href == mainUrl || href.contains("/genre/") || href.contains("/category/")) return null
        val title = (selectFirst(".film-name, .dynamic-name, h3, h2, .title")?.text()
            ?: a.attr("title").ifBlank { a.text() }).trim()
        if (title.isBlank()) return null
        val img = selectFirst("img")
        val poster = abs(img?.attr("data-src")?.ifBlank { img.attr("data-lazy-src").ifBlank { img.attr("src") } })
        val type = if (href.contains("movie", true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, href, type) { posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data + (if (request.data.contains("?")) "&" else "?") + "page=$page"
        val doc = app.get(url, referer = "$mainUrl/").document
        val items = doc.select(".flw-item, .film_list-wrap .item, .film_list .item, .item, article")
            .mapNotNull { it.result() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        for (url in listOf("$mainUrl/search?keyword=$q", "$mainUrl/search?name=$q", "$mainUrl/?s=$q")) {
            try {
                val found = app.get(url, referer = "$mainUrl/").document
                    .select(".flw-item, .film_list-wrap .item, .film_list .item, .item, article")
                    .mapNotNull { it.result() }.distinctBy { it.url }
                if (found.isNotEmpty()) return found
            } catch (_: Exception) { }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = abs(url) ?: url
        val doc = app.get(pageUrl, referer = "$mainUrl/").document
        val title = (doc.selectFirst("h1, .film-name, .film-title")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()).substringBefore(" | ").trim()
        val poster = abs(doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".film-poster img, .anis-poster img")?.let { it.attr("data-src").ifBlank { it.attr("src") } })
        val plot = doc.selectFirst(".description, .film-description, .film-desk, .synopsis")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
        val eps = doc.select("a[href*=/watch/], a[href*=/episode/], .ss-list a, .episodes a, .ep-item a")
            .mapNotNull { a ->
                val epUrl = abs(a.attr("href")) ?: return@mapNotNull null
                val label = a.text().trim().ifBlank { a.attr("title").trim() }
                if (label.isBlank() || epUrl == pageUrl) return@mapNotNull null
                val num = Regex("(?i)(?:episode|ep)\\s*[-:#]?\\s*(\\d+)").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(epUrl) { name = label; episode = num }
            }.distinctBy { it.data }.reversed()
        if (pageUrl.contains("movie", true) || eps.isEmpty()) {
            return newMovieLoadResponse(title, pageUrl, TvType.AnimeMovie, pageUrl) {
                this.posterUrl = poster; this.plot = plot
            }
        }
        return newTvSeriesLoadResponse(title, pageUrl, TvType.Anime, eps) {
            this.posterUrl = poster; this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = abs(data) ?: data
        val doc = app.get(pageUrl, referer = "$mainUrl/").document
        val seenSubs = hashSetOf<String>()
        fun subtitle(raw: String?, label: String?) {
            val url = abs(raw) ?: return
            if (!url.contains(Regex("\\.(vtt|srt|ass|ssa)([?#].*)?$", RegexOption.IGNORE_CASE))) return
            if (seenSubs.add(url)) subtitleCallback(SubtitleFile(label?.ifBlank { "Subtitle" } ?: "Subtitle", url))
        }
        doc.select("track[src], track[kind=subtitles][src], track[kind=captions][src]").forEach {
            val lang = it.attr("srclang")
            val label = it.attr("label").ifBlank { if (lang.equals("id", true) || lang.equals("id-ID", true)) "Indonesian (AI if provided)" else lang }
            subtitle(it.attr("src"), label)
        }
        doc.select("a[href], source[src]").forEach {
            val raw = it.attr("href").ifBlank { it.attr("src") }
            subtitle(raw, it.text().ifBlank { it.attr("title") })
        }

        val frames = doc.select("iframe[src]").mapNotNull { abs(it.attr("src")) }.distinct()
        var found = false
        for (frame in frames) {
            try {
                loadExtractor(frame, pageUrl, subtitleCallback, callback)
                found = true
            } catch (_: Exception) { }
        }
        doc.select("video[src], video source[src], source[src], a[href]").forEach {
            val raw = it.attr("src").ifBlank { it.attr("href") }
            val stream = abs(raw) ?: return@forEach
            if (stream.contains(Regex("\\.(m3u8|mp4|mpd)([?#].*)?$", RegexOption.IGNORE_CASE))) {
                val q = Regex("(1080|720|480|360)").find(stream)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
                callback(newExtractorLink(name, "AniSnatch $q", stream, INFER_TYPE) {
                    this.referer = pageUrl; this.quality = q
                })
                found = true
            }
        }
        return found
    }
}
