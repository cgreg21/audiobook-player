package org.nativescript.audiobookplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class BookMetadataRepository(
    private val context: Context,
    private val database: LibraryDatabase,
) {
    fun apply(books: List<AudioBook>): List<AudioBook> = books.map(::apply)

    fun applyCached(books: List<AudioBook>): List<AudioBook> = books.map { book ->
        database.bookTags(book.id, signature(book))?.let { book.withTags(it) } ?: book
    }

    private fun apply(book: AudioBook): AudioBook {
        val signature = signature(book)
        database.bookTags(book.id, signature)?.let { cached ->
            return book.withTags(cached)
        }

        val names = mutableListOf<String?>()
        var title: String? = null
        var author: String? = null
        var artworkPath: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            book.tracks.forEachIndexed { index, track ->
                try {
                    retriever.setDataSource(context, track.uri)
                    names += retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.trim()?.takeIf(String::isNotEmpty)
                    if (title.isNullOrBlank()) {
                        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            ?.trim()?.takeIf(String::isNotEmpty)
                    }
                    if (author.isNullOrBlank()) {
                        author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                            ?.takeIf(String::isNotBlank)
                            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                ?.takeIf(String::isNotBlank)
                            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                                ?.takeIf(String::isNotBlank)
                    }
                    if (artworkPath == null && index < 3) {
                        artworkPath = writeArtwork(book.id, retriever.embeddedPicture)
                    }
                } catch (_: Exception) {
                    names += null
                }
            }
        } finally {
            retriever.release()
        }

        val distinctNames = names.filterNotNull().distinct()
        val trackTitles = if (distinctNames.size > 1 || (names.size == 1 && distinctNames.isNotEmpty())) names else emptyList()
        val tags = CachedBookTags(
            signature = signature,
            title = title,
            author = author,
            artworkPath = artworkPath,
            trackTitles = trackTitles,
        )
        database.saveBookTags(book.id, tags)
        return book.withTags(tags)
    }

    fun applyOpenLibrary(book: AudioBook, match: OpenLibraryMatch): OpenLibraryMetadataUpdate {
        val signature = signature(book)
        val tags = database.bookTags(book.id, signature)
            ?: CachedBookTags(signature, null, null, null, emptyList())
        val author = if (tags.author.isNullOrBlank()) {
            tags.openLibraryAuthor ?: match.authors.joinToString(", ").takeIf(String::isNotBlank)
        } else {
            tags.openLibraryAuthor
        }
        val withAuthor = tags.copy(openLibraryAuthor = author)
        var artworkPath = tags.openLibraryArtworkPath
        var coverError: String? = null
        if (artworkPath == null && tags.artworkPath == null && match.coverId != null) {
            try {
                artworkPath = OpenLibraryMetadataRepository(context).downloadCover(book.id, match.coverId)
            } catch (error: IOException) {
                coverError = error.localizedMessage ?: "erreur réseau"
            }
        }
        val updated = withAuthor.copy(
            openLibraryArtworkPath = artworkPath,
            openLibraryTitle = match.title.takeIf(String::isNotBlank),
            series = OpenLibraryMetadataRepository(context).series(match),
            genres = match.genres,
        )
        database.saveBookTags(book.id, updated)
        return OpenLibraryMetadataUpdate(book.withTags(updated), coverError)
    }

    private fun signature(book: AudioBook): String =
        book.tracks.joinToString("\n") { it.uri.toString() }

    private fun writeArtwork(bookId: String, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val directory = File(context.filesDir, "covers")
        if (!directory.exists() && !directory.mkdirs()) return null
        val safeName = Integer.toUnsignedString(bookId.hashCode(), 36)
        val target = File(directory, "$safeName.jpg")
        FileOutputStream(target).use { it.write(bytes) }
        return target.absolutePath
    }

    private fun AudioBook.withTags(tags: CachedBookTags): AudioBook {
        val trackTitles = tags.trackTitles
        val tracks = tracks.mapIndexed { index, track ->
            trackTitles.getOrNull(index)?.takeIf(String::isNotBlank)?.let { name ->
                track.copy(name = name)
            } ?: track
        }
        return copy(
            title = tags.title?.takeIf(String::isNotBlank)
                ?: tags.openLibraryTitle?.takeIf(String::isNotBlank) ?: title,
            tracks = tracks,
            author = tags.author?.takeIf(String::isNotBlank) ?: tags.openLibraryAuthor,
            artworkPath = tags.artworkPath ?: tags.openLibraryArtworkPath,
            series = tags.series,
            genres = tags.genres,
            hasOpenLibraryMetadata = !tags.openLibraryAuthor.isNullOrBlank() ||
                !tags.openLibraryArtworkPath.isNullOrBlank() || !tags.openLibraryTitle.isNullOrBlank(),
        )
    }

    companion object {
        fun encodeTrackTitles(titles: List<String?>): String =
            JSONArray().apply {
                titles.forEach { put(it) }
            }.toString()

        fun decodeTrackTitles(encoded: String): List<String?> {
            val array = JSONArray(encoded)
            return List(array.length()) { index ->
                if (array.isNull(index)) null else array.optString(index).takeIf(String::isNotBlank)
            }
        }
    }
}

data class CachedBookTags(
    val signature: String,
    val title: String?,
    val author: String?,
    val artworkPath: String?,
    val trackTitles: List<String?>,
    val openLibraryAuthor: String? = null,
    val openLibraryArtworkPath: String? = null,
    val openLibraryTitle: String? = null,
    val series: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
)

data class OpenLibraryMetadataUpdate(
    val book: AudioBook,
    val coverError: String?,
)
