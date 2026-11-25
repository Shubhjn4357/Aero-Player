package com.emptysol.aeroplayer.torrent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

data class RssFeedItem(
    val title: String,
    val downloadUri: String,
    val pubDate: String = "",
    val matchesFilter: Boolean = true
)

/**
 * Fetches and parses RSS 2.0 & Atom feeds via OkHttp + XmlPullParser, applying
 * include/exclude wildcard or regex rules for automatic torrent enqueueing.
 */
class RssAutoDownloader(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    fun fetchFeedItems(
        feedUrl: String,
        includeFilter: String = "",
        excludeFilter: String = ""
    ): Result<List<RssFeedItem>> {
        return try {
            val req = Request.Builder()
                .url(feedUrl.trim())
                .header("User-Agent", "AeroPlayer-RSS/1.4.8")
                .get()
                .build()
            val xml = okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("RSS HTTP ${resp.code}"))
                }
                resp.body?.string() ?: return Result.failure(IllegalStateException("Empty RSS body"))
            }
            Result.success(parseFeedXml(xml, includeFilter, excludeFilter))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseFeedXml(
        xmlContent: String,
        includeFilter: String = "",
        excludeFilter: String = ""
    ): List<RssFeedItem> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xmlContent))

        val items = mutableListOf<RssFeedItem>()
        var inItem = false
        var currentTag = ""
        var title = ""
        var link = ""
        var enclosureUrl = ""
        var magnetUri = ""
        var pubDate = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    currentTag = name
                    if (name == "item" || name == "entry") {
                        inItem = true
                        title = ""
                        link = ""
                        enclosureUrl = ""
                        magnetUri = ""
                        pubDate = ""
                    } else if (inItem) {
                        if (name == "enclosure") {
                            val urlAttr = parser.getAttributeValue(null, "url").orEmpty()
                            if (urlAttr.isNotBlank()) enclosureUrl = urlAttr
                        } else if (name == "link") {
                            val href = parser.getAttributeValue(null, "href").orEmpty()
                            if (href.isNotBlank()) link = href
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inItem) {
                        val txt = parser.text.orEmpty().trim()
                        if (txt.isNotBlank()) {
                            when (currentTag) {
                                "title" -> title = txt
                                "link" -> if (link.isBlank()) link = txt
                                "torrent:magneturi", "magneturi" -> magnetUri = txt
                                "pubdate", "updated", "published" -> pubDate = txt
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (inItem && (name == "item" || name == "entry")) {
                        val chosenUri = when {
                            magnetUri.startsWith("magnet:", ignoreCase = true) -> magnetUri
                            enclosureUrl.isNotBlank() -> enclosureUrl
                            else -> link
                        }.trim()
                        if (title.isNotBlank() && chosenUri.isNotBlank()) {
                            items.add(
                                RssFeedItem(
                                    title = title,
                                    downloadUri = chosenUri,
                                    pubDate = pubDate,
                                    matchesFilter = matchesFilters(title, includeFilter, excludeFilter)
                                )
                            )
                        }
                        inItem = false
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }
        return items
    }

    fun matchesFilters(title: String, includeFilter: String, excludeFilter: String): Boolean {
        val incTerms = includeFilter.split(",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }
        val excTerms = excludeFilter.split(",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }

        val passesInclude = if (incTerms.isEmpty()) {
            true
        } else {
            incTerms.any { pattern -> matchesPattern(title, pattern) }
        }
        if (!passesInclude) return false

        val blockedByExclude = excTerms.any { pattern -> matchesPattern(title, pattern) }
        return !blockedByExclude
    }

    private fun matchesPattern(text: String, pattern: String): Boolean {
        return try {
            if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length > 2) {
                Regex(pattern.substring(1, pattern.length - 1), RegexOption.IGNORE_CASE).containsMatchIn(text)
            } else if (pattern.contains("*") || pattern.contains("?")) {
                val regexStr = pattern
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".")
                Regex(regexStr, RegexOption.IGNORE_CASE).containsMatchIn(text)
            } else {
                text.contains(pattern, ignoreCase = true)
            }
        } catch (_: Exception) {
            text.contains(pattern, ignoreCase = true)
        }
    }
}
