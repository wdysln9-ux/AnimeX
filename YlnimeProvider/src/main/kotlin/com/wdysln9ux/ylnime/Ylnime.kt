package com.wdysln9ux.ylnime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
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
            listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "data-lazy", "src")
                .asSequence()
                .map { attr -> it.attr(attr).trim() }
                .firstOrNull { src ->
                    src.isNotBlank() &&
                        !src.startsWith("data:image", true) &&
                        !src.contains("placeholder", true) &&
                        !src.contains("default-avatar", true)
                }
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
        val title = (doc.selectFirst("h1, h2.title, .anime-title")
            ?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title())
            .replace(Regex("(?i)^nonton\\s+anime\\s*"), "")
            .replace(Regex("(?i)^nonton\\s*"), "")
            .replace(Regex("(?i)\\s*[-|]\\s*YLnime.*$"), "")
            .replace(Regex("(?i)\\s*sub indo(\\s+hd)?"), "")
            .trim()

        fun imageFrom(element: Element?): String? {
            if (element == null) return null
            return listOf("data-src", "data-lazy-src", "data-original", "data-original-src", "data-lazy", "src", "content")
                .asSequence()
                .map { element.attr(it).trim() }
                .firstOrNull {
                    it.isNotBlank() && !it.startsWith("data:image", true) &&
                        !it.contains("placeholder", true) && !it.contains("default-avatar", true)
                }
                ?.let { normalizeUrl(it) }
        }

        // Prefer actual artwork in the detail page; social metadata can contain
        // the site's generic banner, which was being reused for every anime.
        // YLnime sometimes returns the same generic artwork for multiple titles.
        // Use AniList/MAL tracker metadata first, then the page-specific artwork.
        val tracker = try {
            APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), null, true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        // Animein's tracker exposes these IDs directly; YLnime does not define
        // resolveAnimeIds, so use the same tracker fields without that helper.
        val malId = tracker?.malId
        val aniId = tracker?.aniId?.toIntOrNull()
        val pagePoster = imageFrom(doc.selectFirst(
            ".series-poster img, .anime-poster img, .detail-poster img, .poster img, " +
                ".anime-cover img, .cover-image img, .anime-detail img, img.card-img-top"
        )) ?: imageFrom(doc.selectFirst("meta[property=og:image], meta[name=twitter:image]"))
        val poster = tracker?.image ?: pagePoster

        val pageBackground = imageFrom(doc.selectFirst(
            ".anime-banner img, .detail-banner img, .series-banner img, .backdrop img, " +
                ".background img, .cover-bg img, [style*=background-image]"
        )) ?: imageFrom(doc.selectFirst("meta[property=og:image]"))
        val background = tracker?.cover ?: pageBackground

        fun isBoilerplate(text: String): Boolean {
            val value = text.lowercase()
            return value.contains("nonton streaming") ||
                value.contains("kualitas 360p") ||
                value.contains("kualitas 480p") ||
                value.contains("kualitas 720p") ||
                value.contains("kualitas 1080p") ||
                value.contains("update cepat tanpa iklan") ||
                value.contains("subtitle indonesia, update cepat") ||
                value.contains("gratis di ylnime") ||
                value.contains("di ylnime. kualitas")
        }

        val synopsisSelectors = listOf(
            ".synopsis", "#synopsis", ".anime-synopsis", ".series-synopsis",
            ".description", ".anime-description", ".detail-synopsis",
            "[itemprop=description]", ".plot", ".story", ".anime-story"
        )
        val pageSynopsis = synopsisSelectors.asSequence().mapNotNull { selector ->
            val element = doc.selectFirst(selector) ?: return@mapNotNull null
            element.text().replace(Regex("\\s+"), " ").trim()
        }.firstOrNull {
            it.isNotBlank() && !it.equals(title, true) && !isBoilerplate(it)
        } ?: listOf(
            doc.selectFirst("meta[property=og:description]")?.attr("content"),
            doc.selectFirst("meta[name=description]")?.attr("content")
        ).firstOrNull {
            !it.isNullOrBlank() && !isBoilerplate(it.trim()) && !it.trim().equals(title, true)
        }?.trim()

        // Keep page-specific synopsis when available; YLnime has no shared
        // fetchAniListPlot helper in this provider module.
        val synopsis = pageSynopsis

        fun infoValue(vararg labels: String): String? {
            val wanted = labels.map { it.lowercase().replace(":", "").trim() }
            for (row in doc.select("tr, .info-item, .anime-info li, .detail-info li, .series-info li, .meta-item")) {
                val label = row.selectFirst("th, .label, .name, strong, b")?.text()
                    ?: row.ownText().substringBefore(":")
                if (wanted.any { label.lowercase().replace(":", "").trim().contains(it) }) {
                    val value = row.selectFirst("td, .value, .content")?.text()
                        ?: row.text().substringAfter(":", "").trim()
                    if (value.isNotBlank() && value != row.text().trim()) return value
                }
            }
            return null
        }

        val allText = doc.text()
        val year = Regex("(?:19|20)\\d{2}").find(
            infoValue("tahun", "year", "rilis", "released") ?: allText
        )?.value?.toIntOrNull()
        val duration = Regex("(\\d{1,3})\\s*(?:menit|min)").find(
            infoValue("durasi", "duration") ?: allText
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        val tags = doc.select(
            ".genres a, .genre a, a[href*=genre], .anime-genres a, .badge.genre, .tags a"
        ).map { it.text().trim() }
            .filter { it.length > 1 && !it.contains("episode", true) &&
                !it.contains("sub indo", true) && !it.contains("watch", true) }
            .distinct()

        val ongoing = Regex("(?i)ongoing|sedang tayang|currently airing").containsMatchIn(
            infoValue("status") ?: allText
        )
        val episodeAnchors = doc.select("a[href*=episode=], a[href*=episode-], a[href*=episode/], .episode-list a, .episodes a")
        val episodes = episodeAnchors.mapNotNull { anchor ->
            val epUrl = normalizeUrl(anchor.attr("href")) ?: return@mapNotNull null
            if (epUrl == fixedUrl || epUrl.contains("javascript:", true)) return@mapNotNull null
            val label = anchor.text().replace(Regex("\\s+"), " ").trim()
            val number = Regex("(?i)(?:episode|ep\\.?|eps?)\\s*[-:#]?\\s*(\\d+(?:\\.\\d+)?)")
                .find(label.ifBlank { anchor.attr("title") + " " + epUrl })
                ?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (label.isBlank() && number == null) return@mapNotNull null
            newEpisode(epUrl) {
                name = label.ifBlank { number?.let { "Episode ${it.toInt()}" } ?: "Episode" }
                episode = number?.toInt()
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: 0 }

        val movie = fixedUrl.contains("movie", true) ||
            doc.select(".badge, small, .type").any { it.text().contains("movie", true) } ||
            infoValue("tipe", "type", "format")?.contains("movie", true) == true
        if (movie && episodes.size <= 1) {
            return newMovieLoadResponse(
                title, fixedUrl, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: fixedUrl
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = synopsis
                this.tags = tags.ifEmpty { null }
                this.year = year
                this.duration = duration
            }
        }
        return newTvSeriesLoadResponse(title, fixedUrl, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = synopsis
            this.tags = tags.ifEmpty { null }
            this.year = year
            this.duration = duration
            this.showStatus = if (ongoing) ShowStatus.Ongoing else ShowStatus.Completed
            addMalId(malId)
            addAniListId(aniId)
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
