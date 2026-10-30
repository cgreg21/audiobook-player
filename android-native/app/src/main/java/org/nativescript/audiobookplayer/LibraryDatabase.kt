package org.nativescript.audiobookplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedProgress(val trackIndex: Int, val positionMs: Long, val updatedAt: Long = 0)
data class RecentProgress(val bookId: String, val trackIndex: Int, val positionMs: Long, val updatedAt: Long)

internal data class BookReadingState(
    val progress: SavedProgress = SavedProgress(0, 0),
    val readChapterCount: Int = 0,
) {
    fun isRead(book: AudioBook): Boolean =
        book.tracks.isNotEmpty() && readChapterCount >= book.tracks.size

    fun isInProgress(book: AudioBook): Boolean =
        !isRead(book) && (readChapterCount > 0 || progress.trackIndex > 0 || progress.positionMs > 0)
}

class LibraryDatabase(private val context: Context, databaseName: String = "audiobooks.db") :
    SQLiteOpenHelper(context, databaseName, null, 11) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS folders (uri TEXT PRIMARY KEY)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS progress (
                book_id TEXT PRIMARY KEY,
                track_index INTEGER NOT NULL,
                position_ms INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""",
        )
        createReadChaptersTable(db)
        createPodcastTables(db)
        createBookTagsTable(db)
        createDownloadQueueTable(db)
        createLibrarySnapshotTable(db)
    }

    private fun createReadChaptersTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chapters_read (
                book_id TEXT NOT NULL,
                track_index INTEGER NOT NULL,
                PRIMARY KEY (book_id, track_index)
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS folders (uri TEXT PRIMARY KEY)")
            createPodcastTables(db)
        }
        if (oldVersion < 3) createReadChaptersTable(db)
        if (oldVersion < 4) createBookTagsTable(db)
        if (oldVersion < 5) createDownloadQueueTable(db)
        if (oldVersion == 5) {
            db.execSQL("ALTER TABLE download_queue ADD COLUMN mobile_allowed INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE book_tags ADD COLUMN open_library_author TEXT")
            db.execSQL("ALTER TABLE book_tags ADD COLUMN open_library_artwork_path TEXT")
        }
        if (oldVersion < 8) createLibrarySnapshotTable(db)
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE podcasts ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE episodes ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE episodes ADD COLUMN published_at TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE episodes ADD COLUMN duration TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE episodes ADD COLUMN artwork TEXT")
        }
        if (oldVersion in 4..9) {
            db.execSQL("ALTER TABLE book_tags ADD COLUMN open_library_title TEXT")
        }
        if (oldVersion in 4..10) {
            db.execSQL("ALTER TABLE book_tags ADD COLUMN series TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE book_tags ADD COLUMN genres TEXT NOT NULL DEFAULT '[]'")
        }
    }

    private fun createLibrarySnapshotTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS library_snapshot (
                tree_uri TEXT PRIMARY KEY,
                books_json TEXT NOT NULL
            )""",
        )
    }

    fun librarySnapshot(treeUri: String): List<AudioBook>? =
        readableDatabase.query(
            "library_snapshot",
            arrayOf("books_json"),
            "tree_uri = ?",
            arrayOf(treeUri),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                BookMetadataRepository(context, this).applyCached(AudioLibrary.decodeBooks(cursor.getString(0)))
            } else null
        }

    fun saveLibrarySnapshot(treeUri: String, books: List<AudioBook>) {
        writableDatabase.insertWithOnConflict(
            "library_snapshot",
            null,
            ContentValues().apply {
                put("tree_uri", treeUri)
                put("books_json", AudioLibrary.encodeBooks(books))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun createDownloadQueueTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS download_queue (
                queue_key TEXT PRIMARY KEY,
                feed_url TEXT NOT NULL,
                episode_id TEXT NOT NULL,
                title TEXT NOT NULL,
                status TEXT NOT NULL,
                done INTEGER NOT NULL,
                total INTEGER NOT NULL,
                error TEXT,
                mobile_allowed INTEGER NOT NULL DEFAULT 0
            )""",
        )
    }

    private fun createBookTagsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS book_tags (
                book_id TEXT PRIMARY KEY,
                signature TEXT NOT NULL,
                title TEXT,
                author TEXT,
                artwork_path TEXT,
                track_titles TEXT NOT NULL,
                open_library_author TEXT,
                open_library_artwork_path TEXT,
                open_library_title TEXT,
                series TEXT NOT NULL DEFAULT '[]',
                genres TEXT NOT NULL DEFAULT '[]'
            )""",
        )
    }

    private fun createPodcastTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS podcasts (
                feed_url TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                artwork TEXT,
                position INTEGER NOT NULL,
                description TEXT NOT NULL DEFAULT ''
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS episodes (
                id TEXT NOT NULL,
                feed_url TEXT NOT NULL REFERENCES podcasts(feed_url) ON DELETE CASCADE,
                title TEXT NOT NULL,
                remote_url TEXT NOT NULL,
                local_path TEXT,
                position INTEGER NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                published_at TEXT NOT NULL DEFAULT '',
                duration TEXT NOT NULL DEFAULT '',
                artwork TEXT,
                PRIMARY KEY (feed_url, id)
            )""",
        )
    }

    fun podcasts(): List<SavedPodcast> = loadPodcasts()

    fun podcast(feedUrl: String): SavedPodcast? = loadPodcasts(feedUrl).firstOrNull()

    private fun loadPodcasts(feedUrl: String? = null): List<SavedPodcast> = readableDatabase.query(
        "podcasts",
        arrayOf("feed_url", "title", "author", "artwork", "description"),
        if (feedUrl != null) "feed_url = ?" else null,
        feedUrl?.let { arrayOf(it) },
        null,
        null,
        "position",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val podcast = SavedPodcast(
                    feedUrl = cursor.getString(0),
                    title = cursor.getString(1),
                    author = cursor.getString(2),
                    artwork = if (cursor.isNull(3)) null else cursor.getString(3),
                    episodes = emptyList(),
                    description = cursor.getString(4),
                )
                add(podcast.copy(episodes = episodes(podcast.feedUrl)))
            }
        }
    }

    private fun episodes(feedUrl: String): List<SavedEpisode> =
        readableDatabase.query(
            "episodes",
            arrayOf("id", "title", "remote_url", "local_path", "description", "published_at", "duration", "artwork"),
            "feed_url = ?",
            arrayOf(feedUrl),
            null,
            null,
            "position",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SavedEpisode(
                            id = cursor.getString(0),
                            title = cursor.getString(1),
                            remoteUrl = cursor.getString(2),
                            localPath = if (cursor.isNull(3)) null else cursor.getString(3),
                            description = cursor.getString(4),
                            publishedAt = cursor.getString(5),
                            duration = cursor.getString(6),
                            artwork = if (cursor.isNull(7)) null else cursor.getString(7),
                        ),
                    )
                }
            }
        }

    fun savePodcast(podcast: SavedPodcast, position: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "podcasts",
                null,
                ContentValues().apply {
                    put("feed_url", podcast.feedUrl)
                    put("title", podcast.title)
                    put("author", podcast.author)
                    put("artwork", podcast.artwork)
                    put("description", podcast.description)
                    put("position", position)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.delete("episodes", "feed_url = ?", arrayOf(podcast.feedUrl))
            podcast.episodes.forEachIndexed { index, episode ->
                db.insertOrThrow(
                    "episodes",
                    null,
                    ContentValues().apply {
                        put("id", episode.id)
                        put("feed_url", podcast.feedUrl)
                        put("title", episode.title)
                        put("remote_url", episode.remoteUrl)
                        put("local_path", episode.localPath)
                        put("description", episode.description)
                        put("published_at", episode.publishedAt)
                        put("duration", episode.duration)
                        put("artwork", episode.artwork)
                        put("position", index)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deletePodcast(feedUrl: String) {
        writableDatabase.delete("episodes", "feed_url = ?", arrayOf(feedUrl))
        writableDatabase.delete("podcasts", "feed_url = ?", arrayOf(feedUrl))
    }

    fun setSetting(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "settings",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun setting(key: String, fallback: String): String =
        readableDatabase.query(
            "settings",
            arrayOf("value"),
            "key = ?",
            arrayOf(key),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else fallback
        }

    fun bookTags(bookId: String, signature: String): CachedBookTags? =
        readableDatabase.query(
            "book_tags",
            arrayOf(
                "signature", "title", "author", "artwork_path", "track_titles",
                "open_library_author", "open_library_artwork_path",
                "open_library_title",
                "series", "genres",
            ),
            "book_id = ? AND signature = ?",
            arrayOf(bookId, signature),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            CachedBookTags(
                signature = cursor.getString(0),
                title = if (cursor.isNull(1)) null else cursor.getString(1),
                author = if (cursor.isNull(2)) null else cursor.getString(2),
                artworkPath = if (cursor.isNull(3)) null else cursor.getString(3),
                trackTitles = BookMetadataRepository.decodeTrackTitles(cursor.getString(4)),
                openLibraryAuthor = if (cursor.isNull(5)) null else cursor.getString(5),
                openLibraryArtworkPath = if (cursor.isNull(6)) null else cursor.getString(6),
                openLibraryTitle = if (cursor.isNull(7)) null else cursor.getString(7),
                series = BookMetadataRepository.decodeTrackTitles(cursor.getString(8)).filterNotNull(),
                genres = BookMetadataRepository.decodeTrackTitles(cursor.getString(9)).filterNotNull(),
            )
        }

    fun saveBookTags(bookId: String, tags: CachedBookTags) {
        writableDatabase.insertWithOnConflict(
            "book_tags",
            null,
            ContentValues().apply {
                put("book_id", bookId)
                put("signature", tags.signature)
                put("title", tags.title)
                put("author", tags.author)
                put("artwork_path", tags.artworkPath)
                put("track_titles", BookMetadataRepository.encodeTrackTitles(tags.trackTitles))
                put("open_library_author", tags.openLibraryAuthor)
                put("open_library_artwork_path", tags.openLibraryArtworkPath)
                put("open_library_title", tags.openLibraryTitle)
                put("series", BookMetadataRepository.encodeTrackTitles(tags.series))
                put("genres", BookMetadataRepository.encodeTrackTitles(tags.genres))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun downloadQueue(): List<PodcastDownload> =
        readableDatabase.query(
            "download_queue",
            arrayOf(
                "queue_key", "feed_url", "episode_id", "title", "status",
                "done", "total", "error", "mobile_allowed",
            ),
            null,
            null,
            null,
            null,
            "rowid",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val status = runCatching { DownloadStatus.valueOf(cursor.getString(4)) }
                        .getOrDefault(DownloadStatus.QUEUED)
                    add(
                        PodcastDownload(
                            key = cursor.getString(0),
                            feedUrl = cursor.getString(1),
                            episodeId = cursor.getString(2),
                            title = cursor.getString(3),
                            status = status,
                            done = cursor.getLong(5),
                            total = cursor.getLong(6),
                            error = if (cursor.isNull(7)) null else cursor.getString(7),
                            mobileAllowed = cursor.getInt(8) != 0,
                        ),
                    )
                }
            }
        }

    fun saveDownloadQueue(downloads: List<PodcastDownload>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("download_queue", null, null)
            downloads.forEach { download ->
                db.insertOrThrow(
                    "download_queue",
                    null,
                    ContentValues().apply {
                        put("queue_key", download.key)
                        put("feed_url", download.feedUrl)
                        put("episode_id", download.episodeId)
                        put("title", download.title)
                        put("status", download.status.name)
                        put("done", download.done)
                        put("total", download.total)
                        put("error", download.error)
                        put("mobile_allowed", if (download.mobileAllowed) 1 else 0)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun treeUri(): String? {
        readableDatabase.query(
            "settings",
            arrayOf("value"),
            "key = ?",
            arrayOf("library_tree_uri"),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }

        return readableDatabase.query(
            "folders",
            arrayOf("uri"),
            null,
            null,
            null,
            null,
            "rowid DESC",
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun saveTreeUri(uri: String) {
        writableDatabase.insertWithOnConflict(
            "settings",
            null,
            ContentValues().apply {
                put("key", "library_tree_uri")
                put("value", uri)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    internal fun readingStates(bookIds: Collection<String>): Map<String, BookReadingState> {
        val states = bookIds.associateWith { BookReadingState() }.toMutableMap()
        val db = readableDatabase
        states.keys.chunked(900).forEach { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            val arguments = ids.toTypedArray()
            db.query(
                "progress",
                arrayOf("book_id", "track_index", "position_ms", "updated_at"),
                "book_id IN ($placeholders)", arguments, null, null, null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    states[id] = states.getValue(id).copy(
                        progress = SavedProgress(cursor.getInt(1), cursor.getLong(2), cursor.getLong(3)),
                    )
                }
            }
            db.query(
                "chapters_read",
                arrayOf("book_id", "COUNT(*)"),
                "book_id IN ($placeholders)", arguments, "book_id", null, null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    states[id] = states.getValue(id).copy(readChapterCount = cursor.getInt(1))
                }
            }
        }
        return states
    }

    fun progress(bookId: String): SavedProgress {
        return readableDatabase.query(
            "progress",
            arrayOf("track_index", "position_ms", "updated_at"),
            "book_id = ?",
            arrayOf(bookId),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                SavedProgress(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2))
            } else {
                SavedProgress(0, 0)
            }
        }

    }

    fun lastPlayed(): RecentProgress? =
        readableDatabase.query(
            "progress",
            arrayOf("book_id", "track_index", "position_ms", "updated_at"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                RecentProgress(cursor.getString(0), cursor.getInt(1), cursor.getLong(2), cursor.getLong(3))
            } else {
                null
            }
        }

    fun saveProgress(bookId: String, trackIndex: Int, positionMs: Long) {
        writableDatabase.insertWithOnConflict(
            "progress",
            null,
            ContentValues().apply {
                put("book_id", bookId)
                put("track_index", trackIndex)
                put("position_ms", positionMs)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun readChapters(bookId: String): Set<Int> =
        readableDatabase.query(
            "chapters_read",
            arrayOf("track_index"),
            "book_id = ?",
            arrayOf(bookId),
            null,
            null,
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getInt(0))
            }
        }

    fun markChapterRead(bookId: String, trackIndex: Int) {
        writableDatabase.insertWithOnConflict(
            "chapters_read",
            null,
            ContentValues().apply {
                put("book_id", bookId)
                put("track_index", trackIndex)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun setBookRead(bookId: String, trackCount: Int, read: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("chapters_read", "book_id = ?", arrayOf(bookId))
            if (read) {
                repeat(trackCount) { index ->
                    db.insertWithOnConflict(
                        "chapters_read",
                        null,
                        ContentValues().apply {
                            put("book_id", bookId)
                            put("track_index", index)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
