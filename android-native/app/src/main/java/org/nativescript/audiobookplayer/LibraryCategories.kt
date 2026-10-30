package org.nativescript.audiobookplayer

internal enum class LibraryCategory {
    AUTHORS,
    GENRES,
    SERIES;

    fun values(book: AudioBook): List<String> = when (this) {
        AUTHORS -> listOfNotNull(book.author?.takeIf(String::isNotBlank))
        GENRES -> book.genres
        SERIES -> book.series
    }
}

internal data class LibraryGroup(val label: String, val books: List<AudioBook>)

internal object LibraryCategories {
    private val combiningMarks = Regex("\\p{M}+")

    private class GroupBuilder(val label: String) {
        val books = linkedMapOf<String, AudioBook>()

        fun build() = LibraryGroup(label, books.values.toList())
    }

    fun key(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(combiningMarks, "").trim().lowercase(java.util.Locale.ROOT)

    fun groups(library: List<AudioBook>, category: LibraryCategory): List<LibraryGroup> {
        val groups = linkedMapOf<String, GroupBuilder>()
        library.forEach { book ->
            category.values(book).filter(String::isNotBlank).forEach { value ->
                val label = value.trim()
                groups.getOrPut(key(label)) { GroupBuilder(label) }.books.getOrPut(book.id) { book }
            }
        }
        return groups.values.map { it.build() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun matching(library: List<AudioBook>, category: LibraryCategory, value: String): List<AudioBook> {
        val target = key(value)
        return library.filter { book -> category.values(book).any { key(it) == target } }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}
