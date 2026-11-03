package com.wdysln9ux.kaa

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KaaProvider : MainAPI() {
    override var mainUrl = "https://kaa.lt"
    override var name = "KickAssAnime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "recent" to "Recently Added"
    )

    private val gson = Gson()
    private val headers = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    private fun JsonElement?.stringOrNull(): String? =
        this?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonElement?.intOrNull(): Int? =
        this?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private suspend fun searchJson(query: String, page: Int = 1): JsonArray {
        val body = gson.toJson(mapOf("query" to query, "page" to page))
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val response = app.post("$mainUrl/api/fsearch", requestBody = body, headers = headers)
        val root = JsonParser.parseString(response.text).asJsonObject
        return root.array("result") ?: JsonArray()
    }

    private fun posterUrl(key: String?): String? =
        key?.takeIf { it.isNotBlank() }?.let { "$mainUrl/image/poster/$it.webp" }

    private fun episodePosterUrl(key: String?): String? =
        key?.takeIf { it.isNotBlank() }?.let { "$mainUrl/image/thumbnail/$it.webp" }

    private fun JsonObject.toSearchResponse(): SearchResponse? {
        val slug = get("slug").stringOrNull() ?: return null
        val title = get("title").stringOrNull()
            ?: get("title_en").stringOrNull()
            ?: return null
        val poster = posterUrl(obj("poster")?.get("hq").stringOrNull())
        val type = when (get("type").stringOrNull()?.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova" -> TvType.OVA
            else -> TvType.Anime
        }
        return newAnimeSearchResponse(title, slug, type) {
            posterUrl = poster
        }
    }

    private fun Element.toRecentSearchResponse(): SearchResponse? {
        val href = selectFirst("a[href]")?.absUrl("href")?.takeIf {
            it.startsWith(mainUrl) && (it.contains("/anime/") || it.contains("/show/"))
        } ?: return null
        val image = selectFirst("img")
        val title = selectFirst("[title]")?.attr("title")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: image?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("h2, h3, .title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        if (title.contains("uncensored", true) || title.contains("hentai", true) || title.contains("adult", true)) return null
        val poster = listOf("src", "data-src", "data-lazy-src", "data-original")
            .firstNotNullOfOrNull { key -> image?.attr(key)?.takeIf { it.isNotBlank() } }
            ?.let { raw -> if (raw.startsWith("//")) "https:$raw" else raw }
        val slug = href.substringAfterLast('/').substringBefore('?')
        val type = if (title.contains("movie", true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, slug, type) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/recent" else "$mainUrl/recent?page=$page"
        val document = Jsoup.parse(app.get(url, headers = headers).text, mainUrl)
        val results = document.select("a[href]")
            .mapNotNull { it.toRecentSearchResponse() }
            .distinctBy { it.url }
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = results,
                isHorizontalImages = false
            ),
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return searchJson(query.trim())
            .mapNotNull { if (it.isJsonObject) it.asJsonObject.toSearchResponse() else null }
            .distinctBy { it.url }
    }

    private data class KaaEpisode(
        val slug: String,
        val number: Int,
        val title: String?,
        val thumbnail: String?
    )

    private suspend fun episodePage(showSlug: String, page: Int, locale: String): JsonObject {
        val url = "$mainUrl/api/show/$showSlug/episodes?ep=$page&lang=$locale"
        return JsonParser.parseString(app.get(url, headers = headers).text).asJsonObject
    }

    private fun parseEpisodes(root: JsonObject): List<KaaEpisode> =
        (root.array("result") ?: JsonArray()).mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val row = item.asJsonObject
            val slug = row.get("slug").stringOrNull() ?: return@mapNotNull null
            val number = row.get("episode_number").intOrNull() ?: return@mapNotNull null
            val thumbnail = row.obj("thumbnail")?.get("hq").stringOrNull()
            KaaEpisode(slug, number, row.get("title").stringOrNull(), thumbnail)
        }

    private suspend fun getAllEpisodes(showSlug: String): List<KaaEpisode> {
        val locale = "ja-JP"
        val first = episodePage(showSlug, 1, locale)
        val pageNumbers = (first.array("pages") ?: JsonArray()).mapNotNull { page ->
            if (!page.isJsonObject) return@mapNotNull null
            page.asJsonObject.get("number").intOrNull()
        }.distinct().sorted()
        val pages = mutableListOf(first)
        for (page in pageNumbers.filter { it > 1 }) {
            pages += episodePage(showSlug, page, locale)
        }
        val episodes = pages.flatMap(::parseEpisodes).distinctBy { it.number }.sortedBy { it.number }
        if (episodes.isNotEmpty()) return episodes

        // Some titles only publish an English audio listing.
        val english = episodePage(showSlug, 1, "en-US")
        val englishPages = (english.array("pages") ?: JsonArray()).mapNotNull { page ->
            if (!page.isJsonObject) return@mapNotNull null
            page.asJsonObject.get("number").intOrNull()
        }.distinct().sorted()
        val fallbackPages = mutableListOf(english)
        for (page in englishPages.filter { it > 1 }) {
            fallbackPages += episodePage(showSlug, page, "en-US")
        }
        return fallbackPages.flatMap(::parseEpisodes).distinctBy { it.number }.sortedBy { it.number }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.substringAfterLast('/').substringBefore('?').trim()
        if (slug.isBlank()) throw ErrorLoadingException("Invalid KickAssAnime title")

        val row = searchJson(slug).firstOrNull { item ->
            item.isJsonObject && item.asJsonObject.get("slug").stringOrNull() == slug
        }?.asJsonObject

        val title = row?.get("title").stringOrNull()
            ?: row?.get("title_en").stringOrNull()
            ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
        val poster = posterUrl(row?.obj("poster")?.get("hq").stringOrNull())
        val episodeRows = getAllEpisodes(slug)
        val episodes = episodeRows.map { epRow ->
            val data = gson.toJson(
                mapOf(
                    "show" to slug,
                    "episodeSlug" to epRow.slug,
                    "number" to epRow.number
                )
            )
            newEpisode(data) {
                name = epRow.title?.takeIf { it.isNotBlank() } ?: "Episode ${epRow.number}"
                episode = epRow.number
                posterUrl = episodePosterUrl(epRow.thumbnail)
            }
        }.reversed()

        if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found for $title")

        val type = when (row?.get("type").stringOrNull()?.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova" -> TvType.OVA
            else -> TvType.Anime
        }
        if (type == TvType.AnimeMovie && episodes.size == 1) {
            return newMovieLoadResponse(title, slug, type, episodes.first().data) {
                posterUrl = poster
            }
        }
        return newTvSeriesLoadResponse(title, slug, type, episodes) {
            posterUrl = poster
        }
    }

    private fun decodeAstro(value: JsonElement): JsonElement {
        if (!value.isJsonArray) return value
        val tuple = value.asJsonArray
        if (tuple.size() != 2 || !tuple[0].isJsonPrimitive) return value
        val tag = tuple[0].asInt
        val payload = tuple[1]
        return when (tag) {
            1 -> JsonArray().also { out ->
                if (payload.isJsonArray) payload.asJsonArray.forEach { out.add(decodeAstro(it)) }
            }
            0 -> if (payload.isJsonObject) JsonObject().also { out ->
                payload.asJsonObject.entrySet().forEach { (key, child) -> out.add(key, decodeAstro(child)) }
            } else payload
            else -> value
        }
    }

    private fun normalizeHttps(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("https:////") -> value.replaceFirst(Regex("^https:/*"), "https://")
            else -> value
        }
        return runCatching {
            val uri = URI(candidate)
            if (uri.scheme == "https" && !uri.host.isNullOrBlank()) uri.toString() else null
        }.getOrNull()
    }

    private data class KaaServer(val name: String, val src: String)

    private suspend fun getServers(showSlug: String, number: Int, episodeSlug: String): List<KaaServer> {
        val endpoint = "$mainUrl/api/show/$showSlug/episode/ep-$number-$episodeSlug"
        val root = JsonParser.parseString(app.get(endpoint, headers = headers).text).asJsonObject
        return (root.array("servers") ?: JsonArray()).mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val row = item.asJsonObject
            val serverName = row.get("name").stringOrNull() ?: return@mapNotNull null
            val src = normalizeHttps(row.get("src").stringOrNull()) ?: return@mapNotNull null
            KaaServer(serverName, src)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeData = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
            ?: return false
        val showSlug = episodeData.get("show").stringOrNull() ?: return false
        val episodeSlug = episodeData.get("episodeSlug").stringOrNull() ?: return false
        val number = episodeData.get("number").intOrNull() ?: return false

        val server = getServers(showSlug, number, episodeSlug)
            .firstOrNull { it.name.equals("CatStream", true) || it.name.equals("VidStreaming", true) }
            ?: return false
        val playerHtml = app.get(
            server.src,
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to headers.getValue("User-Agent")
            )
        ).text
        val playerOrigin = runCatching {
            val uri = URI(server.src)
            "${uri.scheme}://${uri.authority}"
        }.getOrNull() ?: return false

        val propsElement = org.jsoup.Jsoup.parse(playerHtml)
            .select("astro-island[props]")
            .firstOrNull { it.attr("props").contains("manifest") }
            ?: return false
        val rawProps = runCatching {
            JsonParser.parseString(propsElement.attr("props")).asJsonObject
        }.getOrNull() ?: return false
        val props = JsonObject()
        rawProps.entrySet().forEach { (key, value) -> props.add(key, decodeAstro(value)) }

        val manifest = normalizeHttps(props.get("manifest").stringOrNull()) ?: return false
        val streamHeaders = mapOf(
            "Referer" to "$playerOrigin/",
            "Origin" to playerOrigin,
            "User-Agent" to headers.getValue("User-Agent")
        )

        (props.array("subtitles") ?: JsonArray()).forEach { track ->
            if (!track.isJsonObject) return@forEach
            val subtitle = track.asJsonObject
            val subtitleUrl = normalizeHttps(subtitle.get("src").stringOrNull()) ?: return@forEach
            val language = subtitle.get("name").stringOrNull()
                ?: subtitle.get("language").stringOrNull()
                ?: "Subtitle"
            subtitleCallback(SubtitleFile(language, subtitleUrl))
        }

        callback.invoke(
            newExtractorLink(name, "KickAssAnime", manifest, ExtractorLinkType.M3U8) {
                this.referer = "$playerOrigin/"
                this.headers = streamHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
