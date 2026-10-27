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

    private fun titleKey(value: String): String = value.lowercase()
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("(?i)season\\s*\\d+|part\\s*\\d+|subtitle indonesia|sub indo"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    // Kitsu supplies episode titles; YLnime remains the source for stream URLs and detail metadata.
    private suspend fun kitsuEpisodeTitles(title: String, year: Int?, episodeNumbers: Set<Int>): Map<Int, String> {
        if (episodeNumbers.isEmpty()) return emptyMap()
        return try {
            val query = java.net.URLEncoder.encode(title, "UTF-8")
            val search = app.get(
                "https://kitsu.io/api/edge/anime?filter[text]=$query&page[limit]=10&sort=popularityRank",
                headers = mapOf("Accept" to "application/vnd.api+json")
            ).text
            val results = org.json.JSONObject(search).optJSONArray("data") ?: return emptyMap()
            val wanted = titleKey(title)
            var chosen: org.json.JSONObject? = null
            var chosenScore = -1
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val attrs = item.optJSONObject("attributes") ?: continue
                val titles = attrs.optJSONObject("titles")
                val names = listOfNotNull(
                    attrs.optString("canonicalTitle").takeIf { it.isNotBlank() },
                    titles?.optString("en_jp")?.takeIf { it.isNotBlank() },
                    titles?.optString("en")?.takeIf { it.isNotBlank() },
                    titles?.optString("ja_jp")?.takeIf { it.isNotBlank() }
                ).map { titleKey(it) }
                val exact = names.any { it == wanted }
                val close = names.any { it.length >= 5 && wanted.length >= 5 && (it.contains(wanted) || wanted.contains(it)) }
                if (!exact && !close) continue
                val foundYear = Regex("^\\d{4}").find(attrs.optString("startDate"))?.value?.toIntOrNull()
                if (year != null && foundYear != null && kotlin.math.abs(year - foundYear) > 1) continue
                val score = (if (exact) 100 else 10) - if (year != null && foundYear != null) kotlin.math.abs(year - foundYear) else 0
                if (score > chosenScore) {
                    chosen = item
                    chosenScore = score
                }
            }
            val anime = chosen ?: return emptyMap()
            val id = anime.optString("id")
            if (id.isBlank()) return emptyMap()
            val maxEpisode = episodeNumbers.maxOrNull() ?: return emptyMap()
            val limit = 20
            val pages = minOf(10, (maxEpisode + limit - 1) / limit)
            val titlesByNumber = mutableMapOf<Int, String>()
            for (page in 0 until pages) {
                val episodesJson = app.get(
                    "https://kitsu.io/api/edge/anime/$id/episodes?page[limit]=$limit&page[offset]=${page * limit}&sort=number",
                    headers = mapOf("Accept" to "application/vnd.api+json")
                ).text
                val data = org.json.JSONObject(episodesJson).optJSONArray("data") ?: continue
                for (i in 0 until data.length()) {
                    val episode = data.optJSONObject(i) ?: continue
                    val attrs = episode.optJSONObject("attributes") ?: continue
                    val number = attrs.optInt("number", -1)
                    val episodeTitle = attrs.optString("canonicalTitle").trim()
                    if (number in episodeNumbers && episodeTitle.isNotBlank() && episodeTitle != "null") {
                        titlesByNumber[number] = episodeTitle
                    }
                }
            }
            titlesByNumber
        } catch (_: Exception) {
            emptyMap()
        }
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

        // Use AniList/ani.zip for artwork and anime metadata; YLnime is only the source
        // for playable pages, episode URLs, and stream links.
        val resolvedIds = resolveAnimeIds(listOf(title), TvType.Anime, null, null, null)
        val aniZip = fetchAniZipMeta(resolvedIds.malId, resolvedIds.aniId)
        val aniList = fetchAniListDetails(resolvedIds.aniId)

        fun externalImage(vararg preferredTypes: String): String? {
            val images = aniZip?.images.orEmpty()
            for (preferred in preferredTypes) {
                val match = images.firstOrNull {
                    it.coverType?.equals(preferred, true) == true && !it.url.isNullOrBlank()
                }?.url
                if (!match.isNullOrBlank()) return match
            }
            return null
        }

        val poster = externalImage("Poster", "Cover", "Key Visual") ?: aniList?.cover
        val background = externalImage("Fanart", "Banner", "Background") ?: aniList?.banner
        val synopsis = aniZip?.description?.takeIf { it.isNotBlank() }
            ?: fetchAniListPlot(resolvedIds.malId, resolvedIds.aniId)
            ?: aniList?.description
        val year = aniList?.year
        val duration = aniList?.duration
        val tags = aniList?.genres.orEmpty()

        val allText = doc.text()
        val ongoing = aniList?.status == "RELEASING"
        val episodeAnchors = doc.select("a[href*=episode=], a[href*=episode-], a[href*=episode/], .episode-list a, .episodes a")
        val rawEpisodes = episodeAnchors.mapNotNull { anchor ->
            val epUrl = normalizeUrl(anchor.attr("href")) ?: return@mapNotNull null
            if (epUrl == fixedUrl || epUrl.contains("javascript:", true)) return@mapNotNull null
            val label = anchor.text().replace(Regex("\\s+"), " ").trim()
            val number = Regex("(?i)(?:episode|ep\\.?|eps?)\\s*[-:#]?\\s*(\\d+(?:\\.\\d+)?)")
                .find(label.ifBlank { anchor.attr("title") + " " + epUrl })
                ?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (label.isBlank() && number == null) return@mapNotNull null
            Triple(epUrl, label, number?.toInt())
        }.distinctBy { it.first }.sortedBy { it.third ?: 0 }

        val kitsuTitles = kitsuEpisodeTitles(title, year, rawEpisodes.mapNotNull { it.third }.toSet())
        val episodes = rawEpisodes.map { (epUrl, pageLabel, number) ->
            newEpisode(epUrl) {
                // Never use YLnime's episode label as the displayed title.
                // Prefer Kitsu's canonical episode title; if unavailable, show a neutral number.
                name = number?.let { kitsuTitles[it] }?.takeIf { it.isNotBlank() }
                    ?: number?.let { "Episode $it" }
                    ?: "Episode"
                episode = number
            }
        }

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
                // YLnime pages may expect either a numeric resolution or a "p" suffix.
                // Try both forms so 1080p is not skipped when the site uses reso=1080.
                listOf("1080", "1080p", "720", "720p", "480", "480p", "360", "360p").forEach { reso ->
                    val candidate = pageUrl + separator + "reso=" + reso
                    if (resolutionUrls.none {
                            it.substringAfter("reso=", "").substringBefore("&").equals(reso, true)
                        }) {
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
