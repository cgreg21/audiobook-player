package org.nativescript.audiobookplayer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URL

internal object PodcastFeedParser {
    fun parse(input: InputStream, feedUrl: String): SavedPodcast {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        val channel = mutableMapOf<String, String>()
        var item: MutableMap<String, String>? = null
        val episodes = linkedMapOf<String, SavedEpisode>()
        var field: String? = null
        var fieldDepth = 0
        var imageDepth = -1
        val text = StringBuilder()
        fun resolve(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { URL(URL(feedUrl), it).toString() }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    if (name == "item") item = mutableMapOf()
                    val values = item ?: channel
                    when (name) {
                        "enclosure" -> if (item != null) {
                            parser.getAttributeValue(null, "url")?.let { values["media"] = it }
                        }
                        "itunes:image", "media:thumbnail" -> {
                            val image = parser.getAttributeValue(null, "href")
                                ?: parser.getAttributeValue(null, "url")
                            if (!image.isNullOrBlank()) values["artwork"] = image
                        }
                        "image" -> imageDepth = parser.depth
                    }
                    if (field == null && (imageDepth < 0 || name == "url") && name in setOf(
                            "title", "guid", "description", "itunes:summary", "content:encoded",
                            "itunes:author", "author", "pubdate", "itunes:duration", "url",
                        )
                    ) {
                        field = name
                        fieldDepth = parser.depth
                        text.clear()
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (field != null) text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val values = item ?: channel
                    if (field != null && parser.depth == fieldDepth) {
                        val value = text.toString().trim()
                        if (field == "url" && imageDepth >= 0) {
                            if (values["artwork"].isNullOrBlank()) values["artwork"] = value
                        } else {
                            values[field] = value
                        }
                        field = null
                    }
                    if (parser.name.equals("image", true)) imageDepth = -1
                    if (parser.name.equals("item", true)) {
                        val media = resolve(values["media"])
                        if (media != null) {
                            val id = values["guid"].orEmpty().ifBlank { media }
                            episodes.putIfAbsent(id, SavedEpisode(
                                id = id,
                                title = values["title"].orEmpty().ifBlank { "Épisode" },
                                remoteUrl = media,
                                description = description(values),
                                publishedAt = values["pubdate"].orEmpty(),
                                duration = values["itunes:duration"].orEmpty(),
                                artwork = resolve(values["artwork"]),
                            ))
                        }
                        item = null
                    }
                }
            }
            event = parser.next()
        }
        return SavedPodcast(
            feedUrl = feedUrl,
            title = channel["title"].orEmpty(),
            author = channel["itunes:author"].orEmpty().ifBlank { channel["author"].orEmpty() },
            artwork = resolve(channel["artwork"]),
            episodes = episodes.values.toList().asReversed(),
            description = description(channel),
        )
    }

    private fun description(values: Map<String, String>): String =
        listOf("content:encoded", "description", "itunes:summary")
            .firstNotNullOfOrNull { values[it]?.takeIf(String::isNotBlank) }.orEmpty()
}
