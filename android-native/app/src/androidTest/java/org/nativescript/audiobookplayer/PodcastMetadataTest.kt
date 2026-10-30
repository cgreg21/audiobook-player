package org.nativescript.audiobookplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastMetadataTest {
    @Test
    fun podcastLookupReturnsOnlyRequestedFeedWithAllEpisodeMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "podcast-lookup-test.db"
        context.deleteDatabase(name)
        try {
            LibraryDatabase(context, name).use { db ->
                val first = SavedPodcast(
                    feedUrl = "https://example.com/feed?a='value'",
                    title = "First", author = "Author", artwork = "https://example.com/cover.jpg",
                    description = "Description",
                    episodes = listOf(SavedEpisode(
                        id = "shared", title = "Episode", remoteUrl = "https://example.com/audio",
                        localPath = "/download.mp3", description = "Summary", publishedAt = "Date",
                        duration = "42:15", artwork = "https://example.com/episode.jpg",
                    ), SavedEpisode(id = "next", title = "Next", remoteUrl = "https://example.com/next")),
                )
                val second = first.copy(feedUrl = "second", title = "Second",
                    episodes = first.episodes.take(1).map { it.copy(title = "Different") })
                db.savePodcast(first, 1)
                db.savePodcast(second, 0)
                assertEquals(first, db.podcast(first.feedUrl))
                assertEquals(second, db.podcast(second.feedUrl))
                assertEquals(listOf(second, first), db.podcasts())
                assertNull(db.podcast("missing"))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun parsesChannelAndEpisodeMetadataWithoutMixingThem() {
        val xml = """
            <rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
                 xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Podcast</title><itunes:author>Auteur</itunes:author>
                <description><![CDATA[<p>Description du podcast</p>]]></description>
                <image><title>Image title</title><url>/cover.jpg</url></image>
                <item><guid>new</guid><title>Nouveau</title>
                  <enclosure url="/new.mp3"/><pubDate>Fri, 30 Oct 2026 10:00:00 GMT</pubDate>
                  <itunes:duration>00:42:15</itunes:duration><itunes:image href="/episode.jpg"/>
                  <description>Résumé</description><content:encoded><![CDATA[<p>Détails</p>]]></content:encoded>
                </item>
                <item><guid>old</guid><title>Ancien</title><enclosure url="/old.mp3"/></item>
                <item><guid>old</guid><title>Doublon</title><enclosure url="/old.mp3"/></item>
                <item><title>Sans audio</title></item>
              </channel>
            </rss>
        """.trimIndent()
        val podcast = PodcastFeedParser.parse(xml.byteInputStream(), "https://example.com/feed.xml")
        assertEquals("Podcast", podcast.title)
        assertEquals("Auteur", podcast.author)
        assertEquals("https://example.com/cover.jpg", podcast.artwork)
        assertTrue(podcast.description.contains("Description du podcast"))
        assertEquals(listOf("old", "new"), podcast.episodes.map { it.id })
        val episode = podcast.episodes.last()
        assertEquals("https://example.com/new.mp3", episode.remoteUrl)
        assertEquals("https://example.com/episode.jpg", episode.artwork)
        assertEquals("00:42:15", episode.duration)
        assertTrue(episode.publishedAt.contains("2026"))
        assertEquals("<p>Détails</p>", episode.description)
    }

    @Test
    fun migratesVersionEightAndPreservesDownloadsAndMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "podcast-migration-test.db"
        context.deleteDatabase(databaseName)
        try {
            context.openOrCreateDatabase(databaseName, 0, null).use { db ->
                db.execSQL("CREATE TABLE podcasts (feed_url TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, artwork TEXT, position INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE book_tags (book_id TEXT PRIMARY KEY)")
                db.execSQL("CREATE TABLE episodes (id TEXT NOT NULL, feed_url TEXT NOT NULL, title TEXT NOT NULL, remote_url TEXT NOT NULL, local_path TEXT, position INTEGER NOT NULL, PRIMARY KEY(feed_url,id))")
                db.execSQL("INSERT INTO podcasts VALUES ('feed','Podcast','Auteur',NULL,0)")
                db.execSQL("INSERT INTO episodes VALUES ('id','feed','Épisode','https://example.com/audio','/download.mp3',0)")
                db.version = 8
            }
            LibraryDatabase(context, databaseName).use { db ->
                val old = db.podcast("feed")!!
                assertEquals("/download.mp3", old.episodes.single().localPath)
                assertEquals("", old.description)
                db.savePodcast(old.copy(
                    description = "Description",
                    episodes = old.episodes.map { it.copy(
                        description = "Résumé", publishedAt = "Date", duration = "42:15",
                        artwork = "https://example.com/episode.jpg",
                    ) },
                ), 0)
            }
            LibraryDatabase(context, databaseName).use { db ->
                val restored = db.podcast("feed")!!
                assertEquals("Description", restored.description)
                val episode = restored.episodes.single()
                assertEquals("/download.mp3", episode.localPath)
                assertEquals("Résumé", episode.description)
                assertEquals("Date", episode.publishedAt)
                assertEquals("42:15", episode.duration)
                assertEquals("https://example.com/episode.jpg", episode.artwork)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
