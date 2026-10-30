package org.nativescript.audiobookplayer

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OpenLibraryMatch(
    val title: String,
    val authors: List<String>,
    val coverId: Int?,
    val genres: List<String> = emptyList(),
    val editionId: String? = null,
)

class OpenLibraryMetadataRepository(private val context: Context) {
    fun search(title: String, author: String?): List<OpenLibraryMatch> {
        val query = buildString {
            append("title=")
            append(encode(title))
            if (!author.isNullOrBlank()) {
                append("&author=")
                append(encode(author))
            }
            append("&limit=5&fields=title,author_name,cover_i,subject,edition_key")
        }
        val response = get("https://openlibrary.org/search.json?$query", MAX_SEARCH_BYTES)
        val docs = JSONObject(String(response, Charsets.UTF_8)).optJSONArray("docs") ?: return emptyList()
        return buildList {
            for (index in 0 until docs.length()) {
                val doc = docs.optJSONObject(index) ?: continue
                val resultTitle = doc.optString("title").trim()
                if (resultTitle.isEmpty()) continue
                val authors = doc.optJSONArray("author_name")?.let { values ->
                    buildList {
                        for (authorIndex in 0 until values.length()) {
                            values.optString(authorIndex).trim()
                                .takeIf(String::isNotEmpty)?.let(::add)
                        }
                    }
                }.orEmpty()
                add(
                    OpenLibraryMatch(
                        title = resultTitle,
                        authors = authors,
                        coverId = doc.optInt("cover_i").takeIf { doc.has("cover_i") && !doc.isNull("cover_i") && it > 0 },
                        genres = doc.optJSONArray("subject")?.let { values ->
                            List(values.length()) { values.optString(it).trim() }.filter(String::isNotBlank).distinct()
                        }.orEmpty(),
                        editionId = doc.optJSONArray("edition_key")?.optString(0)?.takeIf {
                            it.matches(Regex("OL[0-9]+M"))
                        },
                    ),
                )
            }

        }
    }

    fun series(match: OpenLibraryMatch): List<String> {
        val edition = match.editionId ?: return emptyList()
        val json = JSONObject(String(get("https://openlibrary.org/books/$edition.json", MAX_SEARCH_BYTES), Charsets.UTF_8))
        val values = json.optJSONArray("series") ?: return emptyList()
        return List(values.length()) { values.optString(it).trim() }.filter(String::isNotBlank).distinct()
    }

    fun downloadCover(bookId: String, coverId: Int): String? {
        if (coverId <= 0) return null
        val directory = File(context.filesDir, "covers")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Impossible de créer le dossier des couvertures.")
        }
        val safeName = Integer.toUnsignedString(bookId.hashCode(), 36)
        val target = File(directory, "$safeName-openlibrary.jpg")
        val temporary = File(directory, "$safeName-openlibrary.tmp")
        try {
            val bytes = get(
                "https://covers.openlibrary.org/b/id/$coverId-L.jpg?default=false",
                MAX_COVER_BYTES,
            )
            temporary.writeBytes(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight > MAX_COVER_PIXELS
            ) {
                throw IOException("Open Library n’a pas fourni une image de couverture valide.")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            return target.absolutePath
        } catch (error: Exception) {
            temporary.delete()
            if (error is IOException) throw error
            throw IOException("Impossible de télécharger la couverture Open Library.", error)
        }
    }

    private fun get(address: String, maxBytes: Int): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, image/*")
            connection.setRequestProperty("User-Agent", userAgent())
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Open Library a répondu avec le code HTTP $code.")
            }
            if (address.contains("covers.openlibrary.org") &&
                connection.contentType?.startsWith("image/") != true
            ) {
                throw IOException("Open Library n’a pas trouvé de couverture pour ce livre.")
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("La réponse Open Library est trop volumineuse.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun userAgent(): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "unknown"
        return "AudiobookPlayer/$version (+https://github.com/cgreg21/audiobook-player)"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_SEARCH_BYTES = 2 * 1024 * 1024
        private const val MAX_COVER_BYTES = 8 * 1024 * 1024
        private const val MAX_COVER_PIXELS = 20_000_000L
    }
}
