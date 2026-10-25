package com.wdysln9ux.ylnime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.jsoup.nodes.Element

class Ylnime : MainAPI() {
    override var mainUrl = "https://ylnime.com"
    override var name = "YLnime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val instantLinkLoading = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/baru.php" to "Episode Terbaru",
        "$mainUrl/ongoing.php" to "Sedang Tayang",
        "$mainUrl/completed.php" to "Tamat",
        "$mainUrl/movies.php" to "Anime Movie",
        "$mainUrl/anime-list.php" to "Daftar Anime"
    )

    private fun normalizeUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value == "#") return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") ->
                value.replace("https://ylnime.com/?", "https://ylnime.com/index.php?")
            value.startsWith("?") -> "$mainUrl/index.php$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }

    private fun Element.toYlnimeResult(): SearchResponse? {
        val anchor = selectFirst("a.stretched-link")
            ?: selectFirst("a[href*=series=]")
            ?: selectFirst("a[href*=episode=]")
            ?: return null
        val href = normalizeUrl(anchor.attr("href")) ?: return null
        val title = (selectFirst(".card-title, h5, h6")?.text()
            ?: anchor.attr("title").ifBlank { anchor.text() })
            .replace("Nonton Anime ", "", ignoreCase = true)
            .replace("Sub Indo", "", ignoreCase = true)
            .trim()
        if (title.isBlank()) return null
        val image = selectFirst("img")?.let {
            it.attr("src").takeIf { src -> src.isNotBlank() && !src.contains("placeholder", true) }
                ?: it.attr("data-src").ifBlank { it.attr("data-lazy-src") }
        }
        val movie = select("small, .badge").any { it.text().contains("movie", true) } ||
            href.contains("movie", true)
        return newAnimeSearchResponse(title, href, if (movie) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = normalizeUrl(image)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else {
            if (request.data.contains("?")) "${request.data}&page=$page"
            else "${request.data}?page=$page"
        }
        val doc = app.get(pageUrl, referer = "$mainUrl/").document
        val results = doc.select("div.card, div.col-6, div.col-md-3, div.col-lg-2")
            .mapNotNull { it.toYlnimeResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/index.php?search=" +
            java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return app.get(url, referer = "$mainUrl/").document
            .select("div.card, div.col-6, div.col-md-3, div.col-lg-2")
            .mapNotNull { it.toYlnimeResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeUrl(url) ?: url
        val doc = app.get(fixedUrl, referer = "$mainUrl/").document
        val title = (doc.selectFirst("h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title())
            .replace("Nonton Anime ", "", ignoreCase = true)
            .replace("Nonton ", "", ignoreCase = true)
            .replace("Sub Indo HD", "", ignoreCase = true)
            .replace("Sub Indo", "", ignoreCase = true)
            .trim()
        val poster = normalizeUrl(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img.card-img-top, .series-poster img")?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }
                }
        )
        val plot = doc.select("div.synopsis, p.synopsis, div.description, .content p")
            .text().trim().takeIf { it.isNotBlank() }
        val tags = doc.select("div.genres a, span.badge")
            .map { it.text().trim() }
            .filter { it.length > 1 && !it.contains("episode", true) && !it.contains("sub indo", true) }
            .distinct()
        val ongoing = doc.text().contains("Ongoing", true) ||
            doc.text().contains("Sedang Tayang", true)
        val episodes = doc.select("a[href*=episode=]")
            .mapNotNull { a ->
                val epUrl = normalizeUrl(a.attr("href")) ?: return@mapNotNull null
                val label = a.text().replace(Regex("\\s+"), " ").trim()
                val number = Regex("(?i)episode\\s*(\\d+)").find(label)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(epUrl) {
                    name = label.ifBlank { number?.let { "Episode $it" } ?: "Episode" }
                    episode = number
                }
            }.distinctBy { it.data }.sortedBy { it.episode ?: 0 }

        val movie = fixedUrl.contains("movie", true) ||
            doc.select("small, .badge").any { it.text().contains("movie", true) }
        if (movie && episodes.size <= 1) {
            return newMovieLoadResponse(title, fixedUrl, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: fixedUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags.ifEmpty { null }
            }
        }
        return newTvSeriesLoadResponse(title, fixedUrl, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags.ifEmpty { null }
            this.showStatus = if (ongoing) ShowStatus.Ongoing else ShowStatus.Completed
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(data) ?: data
        val collected = mutableListOf<ExtractorLink>()
        val visitedPages = linkedSetOf(pageUrl)

        fun qualityFrom(value: String): Int =
            Regex("(1080|720|480|360)")
                .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value

        suspend fun collectStreams(url: String, html: String) {
            val streams = Regex("""(?s)(?:const|let|var)\s+streams\s*=\s*(\[[\s\S]*?\]);""")
                .find(html)?.groupValues?.getOrNull(1)

            if (streams != null) {
                try {
                    val arr = JSONArray(streams)
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val streamUrl = item.optString("link").replace("\\/", "/").trim()
                        if (!streamUrl.startsWith("http")) continue

                        val quality = qualityFrom(item.optString("reso") + " " + streamUrl)
                        val server = when {
                            streamUrl.contains("pixeldrain", true) -> "Pixeldrain"
                            streamUrl.contains("googlevideo", true) ||
                                streamUrl.contains("blogger", true) -> "Blogger"
                            streamUrl.contains("vidhide", true) -> "VidHide"
                            streamUrl.contains("animekita", true) -> "YLnime CDN"
                            else -> "YLnime Server"
                        }
                        val label = server + if (quality > 0) " " + quality + "p" else ""

                        if (streamUrl.contains(".m3u8", true)) {
                            M3u8Helper.generateM3u8(server, streamUrl, url).forEach { link ->
                                collected.add(newExtractorLink(server, label, link.url, INFER_TYPE) {
                                    this.referer = url
                                    this.quality = quality
                                })
                            }
                        } else {
                            collected.add(newExtractorLink(server, label, streamUrl, INFER_TYPE) {
                                this.referer = url
                                this.quality = quality
                            })
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        try {
            val baseDoc = app.get(pageUrl, referer = "$mainUrl/").document
            collectStreams(pageUrl, baseDoc.html())

            val resolutionUrls = baseDoc.select("a[href*='reso=']")
                .mapNotNull { normalizeUrl(it.attr("href")) }
                .distinct()
                .sortedByDescending { qualityFrom(it) }
                .toMutableList()

            if (!pageUrl.contains("reso=", true)) {
                val separator = if (pageUrl.contains("?")) "&" else "?"
                listOf("1080p", "720p", "480p", "360p").forEach { reso ->
                    val candidate = pageUrl + separator + "reso=" + reso
                    if (resolutionUrls.none { it.contains("reso=" + reso, true) }) {
                        resolutionUrls.add(candidate)
                    }
                }
            }

            for (resolutionUrl in resolutionUrls.distinct()) {
                if (!visitedPages.add(resolutionUrl)) continue
                try {
                    val resolutionDoc = app.get(resolutionUrl, referer = pageUrl).document
                    collectStreams(resolutionUrl, resolutionDoc.html())
                } catch (_: Exception) {
                    // Some episodes do not have every resolution.
                }
            }

            baseDoc.select("video source[src], video[src]").forEach { element ->
                val src = normalizeUrl(element.attr("src")) ?: return@forEach
                collected.add(newExtractorLink("YLnime", "YLnime Direct", src, INFER_TYPE) {
                    this.referer = pageUrl
                    this.quality = qualityFrom(src)
                })
            }

            baseDoc.select("iframe[src]").forEach { iframe ->
                val src = normalizeUrl(iframe.attr("src")) ?: return@forEach
                if (src.contains("ads", true) || src.contains("facebook", true) ||
                    src.contains("cbox", true)) return@forEach
                try {
                    loadExtractor(src, pageUrl, subtitleCallback) { link ->
                        collected.add(link)
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            // Return any links collected before a network error.
        }

        val sortedLinks = collected.distinctBy { it.url }.sortedByDescending { it.quality }
        sortedLinks.forEach(callback)
        return sortedLinks.isNotEmpty()
    }
}
