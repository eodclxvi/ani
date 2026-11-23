package com.anistream.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.Base64
import java.util.concurrent.TimeUnit

object HiAnimeApi {

    private const val BASE_URL = "https://hianime.fm"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val XOR_KEY = "otaku-embed-v1".toByteArray()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun fetch(url: String, referer: String? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (referer != null) builder.header("Referer", referer)
        val response = client.newCall(builder.build()).execute()
        return response.body?.string() ?: ""
    }

    suspend fun search(query: String): List<AnimeResult> = withContext(Dispatchers.IO) {
        val encoded = query.replace(" ", "+")
        val html = fetch("$BASE_URL/search?keyword=$encoded")
        val doc = Jsoup.parse(html)

        val mainContent = doc.selectFirst("#main-content") ?: doc

        val results = mutableListOf<AnimeResult>()
        for (item in mainContent.select(".flw-item")) {
            val link = item.selectFirst(".film-name a") ?: continue
            val href = link.attr("href")
            val title = link.attr("title").ifBlank { link.text() }
            val poster = item.selectFirst(".film-poster img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }
            val path = if (href.startsWith("http")) {
                java.net.URI(href).path
            } else {
                href
            }
            val id = path.substringAfter("/watch/").substringBefore("?").trimStart('/')
            if (id.isNotBlank()) {
                results.add(AnimeResult(id = id, title = cleanHtmlEntities(title), posterUrl = poster))
            }
        }
        results
    }

    suspend fun getEpisodes(animeId: String): List<Episode> = withContext(Dispatchers.IO) {
        val numericId = animeId.substringAfterLast("-")
        val response = fetch("$BASE_URL/api/theme/episode/list/$numericId")

        // API returns JSON: {"status":true, "totalItems":N, "html":"..."}
        val htmlContent = try {
            JSONObject(response).getString("html")
        } catch (_: Exception) {
            response
        }

        val doc = Jsoup.parse(htmlContent)
        val episodes = mutableListOf<Episode>()
        for (el in doc.select("[data-number][data-id]")) {
            val number = el.attr("data-number")
            val dataId = el.attr("data-id")
            if (number.isNotBlank() && dataId.isNotBlank()) {
                episodes.add(Episode(dataId = dataId, number = number))
            }
        }
        episodes
    }

    suspend fun getStreamUrl(
        episodeDataId: String,
        mode: String = "sub",
        preferredQuality: String = "best",
    ): StreamInfo = withContext(Dispatchers.IO) {
        val serversResponse = fetch("$BASE_URL/api/theme/episode/servers?episodeId=$episodeDataId")

        val serversHtml = try {
            JSONObject(serversResponse).getString("html")
        } catch (_: Exception) {
            serversResponse
        }

        val serversDoc = Jsoup.parse(serversHtml)

        val hash = serversDoc.select(".server-item").firstOrNull { el ->
            el.attr("data-type") == mode && el.attr("data-server-name") == "ZokoAnime"
        }?.attr("data-hash") ?: error("No ZokoAnime server found for mode=$mode")

        val embedUrl = String(Base64.getDecoder().decode(hash))
        require(embedUrl.startsWith("http")) { "Invalid embed URL" }

        val referer = run {
            val uri = java.net.URI(embedUrl)
            "${uri.scheme}://${uri.host}/"
        }

        val embedHtml = fetch(embedUrl)
        val blobMatch = Regex("""window\.__P="([^"]+)"""").find(embedHtml)
            ?: error("Could not find __P blob in embed page")
        val blob = blobMatch.groupValues[1]

        val json = deobfuscateBlob(blob)

        val m3u8Master = Regex(""""src":"([^"]*\.m3u8[^"]*)"""").find(json)?.groupValues?.get(1)
            ?: error("No m3u8 URL in config")

        val subtitleUrl = run {
            val subsSection = json.substringAfter("\"subtitles\":[", "")
            if (subsSection.isBlank()) return@run null
            val defaultSub = subsSection.split("},{").firstOrNull { it.contains("\"default\":true") }
            defaultSub?.let { Regex(""""src":"([^"]*)"""").find(it)?.groupValues?.get(1) }
        }

        val masterPlaylist = fetch(m3u8Master, referer = referer)
        val qualities = parseM3u8Qualities(masterPlaylist, m3u8Master)

        val selected = selectQuality(qualities, preferredQuality)

        val audioUrl = Regex("""#EXT-X-MEDIA:TYPE=AUDIO,.*?DEFAULT=YES.*?URI="([^"]*)"""")
            .find(masterPlaylist)?.groupValues?.get(1)?.let { uri ->
                if (uri.startsWith("http")) uri else m3u8Master.substringBeforeLast("/") + "/$uri"
            }

        StreamInfo(
            videoUrl = selected.url,
            audioUrl = audioUrl,
            subtitleUrl = subtitleUrl,
            referer = referer,
            qualities = qualities,
            masterUrl = m3u8Master,
        )
    }

    private fun deobfuscateBlob(base64Blob: String): String {
        val decoded = Base64.getDecoder().decode(base64Blob)
        val output = ByteArray(decoded.size)
        for (i in decoded.indices) {
            output[i] = (decoded[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return String(output)
    }

    private fun parseM3u8Qualities(playlist: String, masterUrl: String): List<Quality> {
        val baseUrl = masterUrl.substringBeforeLast("/")
        val qualities = mutableListOf<Quality>()
        val lines = playlist.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val resolution = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                val urlLine = if (i + 1 < lines.size) lines[i + 1].trim() else ""
                if (resolution != null && urlLine.isNotBlank() && !urlLine.startsWith("#")) {
                    val fullUrl = if (urlLine.startsWith("http")) urlLine else "$baseUrl/$urlLine"
                    qualities.add(Quality(height = resolution, url = fullUrl))
                }
            }
            i++
        }
        return qualities.sortedByDescending { it.height }
    }

    private fun selectQuality(qualities: List<Quality>, preferred: String): Quality {
        if (qualities.isEmpty()) error("No quality variants found")
        return when (preferred) {
            "best" -> qualities.first()
            "worst" -> qualities.last()
            else -> {
                val target = preferred.replace("p", "").toIntOrNull()
                if (target != null) {
                    qualities.firstOrNull { it.height == target } ?: qualities.first()
                } else {
                    qualities.first()
                }
            }
        }
    }

    private fun cleanHtmlEntities(text: String): String =
        text.replace("&#039;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
