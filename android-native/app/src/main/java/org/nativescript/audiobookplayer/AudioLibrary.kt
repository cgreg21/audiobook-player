package org.nativescript.audiobookplayer

import android.content.Context
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class AudioTrack(val name: String, val uri: Uri)

data class AudioBook(
    val id: String,
    val title: String,
    val tracks: List<AudioTrack>,
    val author: String? = null,
    val artworkPath: String? = null,
    val hasOpenLibraryMetadata: Boolean = false,
    val series: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
)

class AudioLibrary(private val context: Context) {
    fun scan(treeUri: Uri): List<AudioBook> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val books = mutableListOf<AudioBook>()
        scanFolder(treeUri, rootId, rootId.substringAfterLast(':').substringAfterLast('/'), books)
        return books
    }

    private fun scanFolder(
        treeUri: Uri,
        documentId: String,
        folderName: String,
        books: MutableList<AudioBook>,
    ) {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val folders = mutableListOf<Pair<String, String>>()
        val tracks = mutableListOf<AudioTrack>()

        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn) ?: continue
                val mime = cursor.getString(mimeColumn)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name != "Podcasts") folders += id to name
                } else if (isAudioFile(name, mime)) {
                    tracks += AudioTrack(
                        name = name,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    )
                }
            }
        }

        val collator = (Collator.getInstance(Locale.getDefault()) as RuleBasedCollator).apply {
            strength = Collator.PRIMARY
            setNumericCollation(true)
        }
        if (tracks.isNotEmpty()) {
            tracks.sortWith { left, right -> collator.compare(left.name, right.name) }
            books += AudioBook("$treeUri|$documentId", folderName.ifBlank { "Livre audio" }, tracks)
        }
        folders.forEach { (id, name) -> scanFolder(treeUri, id, name, books) }
    }

    private fun isAudioFile(name: String, mime: String?): Boolean {
        val supportedExtension = name.substringAfterLast('.', "")
            .lowercase(Locale.ROOT) in setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav")
        return supportedExtension || mime?.startsWith("audio/") == true
    }

    companion object {
        fun encodeBooks(books: List<AudioBook>): String = JSONArray().apply {
            books.forEach { book ->
                put(
                    JSONObject()
                        .put("id", book.id)
                        .put("title", book.title)
                        .put("author", book.author)
                        .put("artworkPath", book.artworkPath)
                        .put("hasOpenLibraryMetadata", book.hasOpenLibraryMetadata)
                        .put("series", JSONArray(book.series))
                        .put("genres", JSONArray(book.genres))
                        .put(
                            "tracks",
                            JSONArray().apply {
                                book.tracks.forEach { track ->
                                    put(JSONObject().put("name", track.name).put("uri", track.uri.toString()))
                                }
                            },
                        ),
                )
            }
        }.toString()

        fun decodeBooks(encoded: String): List<AudioBook> {
            val array = JSONArray(encoded)
            return List(array.length()) { index ->
                val book = array.getJSONObject(index)
                val tracks = book.getJSONArray("tracks")
                AudioBook(
                    id = book.getString("id"),
                    title = book.getString("title"),
                    author = book.optString("author").takeIf(String::isNotBlank),
                    artworkPath = book.optString("artworkPath").takeIf(String::isNotBlank),
                    hasOpenLibraryMetadata = book.optBoolean("hasOpenLibraryMetadata"),
                    series = book.optJSONArray("series")?.let { values ->
                        List(values.length()) { values.getString(it) }
                    }.orEmpty(),
                    genres = book.optJSONArray("genres")?.let { values ->
                        List(values.length()) { values.getString(it) }
                    }.orEmpty(),
                    tracks = List(tracks.length()) { trackIndex ->
                        val track = tracks.getJSONObject(trackIndex)
                        AudioTrack(track.getString("name"), Uri.parse(track.getString("uri")))
                    },
                )
            }
        }
    }
}
