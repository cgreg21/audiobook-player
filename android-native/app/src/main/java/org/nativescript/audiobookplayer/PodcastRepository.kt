package org.nativescript.audiobookplayer

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PodcastSearchResult(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artwork: String?,
)

class PodcastRepository(
    private val context: Context,
    private val database: LibraryDatabase,
) {
    fun search(term: String): List<PodcastSearchResult> {
        val query = java.net.URLEncoder.encode(term, Charsets.UTF_8.name())
        val connection = URL("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=20&term=$query")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val results = json.optJSONArray("results") ?: return emptyList()
            return buildList {
                for (index in 0 until results.length()) {
                    val result = results.optJSONObject(index) ?: continue
                    val feedUrl = result.optString("feedUrl")
                    if (feedUrl.isBlank()) continue
                    add(
                        PodcastSearchResult(
                            feedUrl = feedUrl,
                            title = result.optString("collectionName", "Podcast"),
                            author = result.optString("artistName", ""),
                            artwork = result.optString("artworkUrl600")
                                .ifBlank { result.optString("artworkUrl100") }.takeIf(String::isNotBlank),
                        ),
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun subscribe(result: PodcastSearchResult) {
        if (database.podcast(result.feedUrl) != null) return
        val feed = fetchFeed(result.feedUrl)
        database.savePodcast(
            feed.copy(
                title = feed.title.ifBlank { result.title },
                author = feed.author.ifBlank { result.author },
                artwork = feed.artwork ?: result.artwork,
            ),
            database.podcasts().size,
        )
    }

    fun refresh(feedUrl: String) {
        val current = database.podcast(feedUrl) ?: return
        val feed = fetchFeed(feedUrl)
        val fresh = feed.episodes
        val knownIds = current.episodes.mapTo(mutableSetOf()) { it.id }
        if (fresh.any { it.id !in knownIds }) {
            database.setSetting("newep:$feedUrl", System.currentTimeMillis().toString())
        }
        val downloaded = current.episodes.associate { it.id to it.localPath }
        val updated = feed.copy(
            title = feed.title.ifBlank { current.title },
            author = feed.author.ifBlank { current.author },
            artwork = feed.artwork ?: current.artwork,
            description = feed.description.ifBlank { current.description },
            episodes = fresh.map { it.copy(localPath = downloaded[it.id]) },
        )
        database.savePodcast(updated, database.podcasts().indexOfFirst { it.feedUrl == feedUrl })
    }

    fun unsubscribe(feedUrl: String) {
        val podcast = database.podcast(feedUrl) ?: return
        podcast.episodes.mapNotNull { it.localPath }.forEach(::deleteDownloadedFile)
        database.deletePodcast(feedUrl)
    }

    private fun fetchFeed(feedUrl: String): SavedPodcast {
        val connection = URL(feedUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "AudiobookPlayer/1.0 (Android)")
        try {
            connection.inputStream.use { input ->
                return PodcastFeedParser.parse(input, feedUrl)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun deleteDownloadedFile(path: String) {
        if (path.startsWith("content://")) {
            runCatching {
                android.provider.DocumentsContract.deleteDocument(
                    context.contentResolver,
                    android.net.Uri.parse(path),
                )
            }
        } else {
            java.io.File(path).delete()
        }
    }
}
