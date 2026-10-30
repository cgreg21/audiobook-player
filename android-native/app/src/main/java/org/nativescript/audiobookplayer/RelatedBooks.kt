package org.nativescript.audiobookplayer

internal object RelatedBooks {
    fun sections(book: AudioBook, library: List<AudioBook>): List<Pair<String, List<AudioBook>>> {
        val others = library.filter { it.id != book.id }.distinctBy { it.id }
        fun matching(category: LibraryCategory): List<AudioBook> {
            val keys = category.values(book).map(LibraryCategories::key)
                .filter(String::isNotBlank).toSet()
            if (keys.isEmpty()) return emptyList()
            return others.filter { candidate ->
                category.values(candidate).any { LibraryCategories.key(it) in keys }
            }
        }
        return listOf(
            "Dans la même série" to matching(LibraryCategory.SERIES),
            "Du même auteur" to matching(LibraryCategory.AUTHORS),
            "Dans les mêmes genres" to matching(LibraryCategory.GENRES),
        ).filter { it.second.isNotEmpty() }
    }
}
