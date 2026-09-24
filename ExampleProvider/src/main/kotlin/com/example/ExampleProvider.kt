package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class CartoonNetworkProvider : MainAPI() {
    override var mainUrl = "https://www.wcostream.tv"
    override var name = "CartoonNetwork Vault"
    override val supportedTypes = setOf(TvType.Cartoon)
    override var hasMainPage = true
    override val useMetaProviders = true

    // ------------------------------------------------------------------------
    // 1. CATALOG SEARCH & DIRECTORY
    // ------------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/cartoon-list").document
        val homeItems = mutableListOf<SearchResponse>()

        doc.select("div.ddmcc ul li a, div.cat-single a").take(40).forEach { element ->
            val title = element.text().trim()
            val href = fixUrl(element.attr("href"))
            if (title.isNotEmpty() && href.isNotEmpty()) {
                homeItems.add(newTvSeriesSearchResponse(title, href, TvType.Cartoon) {})
            }
        }
        return newHomePageResponse(listOf(HomePageList("Cartoon Network Series", homeItems)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search"
        val doc = app.post(searchUrl, data = mapOf("catara" to query)).document

        return doc.select("div.cat-single a, ul.display-all-episodes a").mapNotNull { element ->
            val title = element.text().trim()
            val href = fixUrl(element.attr("href"))
            if (title.isEmpty()) null else newTvSeriesSearchResponse(title, href, TvType.Cartoon) {}
        }
    }

    // ------------------------------------------------------------------------
    // 2. EPISODE LOADER (Splits double segments, captures unnumbered titles)
    // ------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val showTitle = doc.selectFirst("h1, div.cat-name")?.text()?.trim() ?: "Cartoon Network"
        val poster = fixUrlNull(doc.selectFirst("img.series-image, div.picture img")?.attr("src"))

        val episodesList = mutableListOf<Episode>()
        var episodeIndex = 1

        val episodeElements = doc.select("div.single-episode a, ul.display-all-episodes a, #val-episodes a")

        // Parse from Episode 1 forward
        episodeElements.reversed().forEach { element ->
            val rawTitle = element.text().trim()
            val pageUrl = fixUrl(element.attr("href"))

            if (pageUrl.isNotEmpty()) {
                // HANDLE SPLIT SEGMENTS (e.g., "The DVD / The Shell")
                if (rawTitle.contains("/") || rawTitle.contains(" & ")) {
                    val segments = rawTitle.split(Regex("""\s*[/&]\s*"""))
                    segments.forEachIndexed { subIdx, subTitle ->
                        val cleanSub = cleanTitle(subTitle, showTitle, episodeIndex)
                        episodesList.add(
                            Episode(
                                data = "$pageUrl#part=${subIdx + 1}",
                                name = cleanSub,
                                season = 1,
                                episode = episodeIndex
                            )
                        )
                        episodeIndex++
                    }
                } else {
                    // HANDLE UNNUMBERED OR REGULAR EPISODES
                    val cleanEpTitle = cleanTitle(rawTitle, showTitle, episodeIndex)
                    val epNum = Regex("""(?i)Episode\s+(\d+)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull() ?: episodeIndex

                    episodesList.add(
                        Episode(
                            data = pageUrl,
                            name = cleanEpTitle,
                            season = 1,
                            episode = epNum
                        )
                    )
                    episodeIndex++
                }
            }
        }

        return newTvSeriesLoadResponse(showTitle, url, TvType.Cartoon, episodesList) {
            this.posterUrl = poster
        }
    }

    // ------------------------------------------------------------------------
    // 3. DIRECT STREAM EXTRACTOR & SUBTITLE INJECTION (Arabic & English)
    // ------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = data.substringBefore("#part=")
        val doc = app.get(cleanUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).document

        // Locate video iframe embed
        val iframeUrl = doc.selectFirst("iframe[src*='inc/embed'], iframe#frame5")?.attr("src")
            ?: doc.selectFirst("iframe")?.attr("src")

        if (!iframeUrl.isNullOrEmpty()) {
            val fullEmbedUrl = fixUrl(iframeUrl)
            val embedDoc = app.get(
                fullEmbedUrl,
                headers = mapOf("Referer" to mainUrl, "User-Agent" to "Mozilla/5.0")
            ).document

            // Decode high-bitrate stream file
            val scriptHtml = embedDoc.select("script").html()
            val m3u8Url = Regex("""file:\s*"([^"]+\.m3u8)"""").find(scriptHtml)?.groupValues?.get(1)
                ?: Regex("""src\s*=\s*"([^"]+\.mp4)"""").find(scriptHtml)?.groupValues?.get(1)

            if (!m3u8Url.isNullOrEmpty()) {
                // AUTOMATIC SUBTITLE FETCHING (Arabic & English)
                embedDoc.select("track[kind=subtitles], track[kind=captions]").forEach { track ->
                    val subUrl = fixUrlNull(track.attr("src"))
                    val label = track.attr("label").ifEmpty { track.attr("srclang") }
                    
                    if (!subUrl.isNullOrEmpty()) {
                        val lang = when {
                            label.contains("ar", ignoreCase = true) || label.contains("arabic", ignoreCase = true) -> "Arabic"
                            else -> "English"
                        }
                        subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                    }
                }

                // Return Direct Video Link
                callback.invoke(
                    ExtractorLink(
                        name = "WCO High Bitrate CDN",
                        source = this.name,
                        url = m3u8Url,
                        referer = fullEmbedUrl,
                        quality = Qualities.P1080.value,
                        isM3u8 = m3u8Url.endsWith(".m3u8")
                    )
                )
                return true
            }
        }
        return false
    }

    private fun cleanTitle(raw: String, showTitle: String, fallbackIndex: Int): String {
        var clean = raw
            .replace(Regex("""(?i)${Regex.escape(showTitle)}"""), "")
            .replace(Regex("""(?i)Season\s+\d+"""), "")
            .replace(Regex("""(?i)Episode\s+\d+"""), "")
            .replace(Regex("""(?i)\(Dub\)|\(Sub\)|English"""), "")
            .replace(Regex("""^[\s\-\:]+"""), "")
            .trim()

        return if (clean.isEmpty()) "Episode $fallbackIndex" else clean
    }
}
