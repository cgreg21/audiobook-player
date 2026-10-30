package org.nativescript.audiobookplayer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookMetadataTest {
    @Test
    fun categoriesPreserveLabelsOrderAndFirstBookWhileMatchingNormalizedValues() {
        val first = AudioBook("a", "Z", emptyList(), author = " Écrivain ",
            series = listOf(" Cycle ", "CYCLE"), genres = listOf("Roman", " "))
        val second = AudioBook("b", "A", emptyList(), author = "écrivain",
            series = listOf("cycle"), genres = listOf("Essai", "roman"))
        val duplicate = first.copy(title = "Duplicate")
        val library = listOf(first, second, duplicate)
        val authors = LibraryCategories.groups(library, LibraryCategory.AUTHORS)
        assertEquals(listOf("Écrivain"), authors.map { it.label })
        assertEquals(listOf(first, second), authors.single().books)
        assertEquals(listOf("Essai", "Roman"), LibraryCategories.groups(library, LibraryCategory.GENRES).map { it.label })
        assertEquals(listOf("Cycle"), LibraryCategories.groups(library, LibraryCategory.SERIES).map { it.label })
        assertEquals(listOf(second, first), LibraryCategories.matching(listOf(first, second), LibraryCategory.AUTHORS, " ECRIVAIN "))
        assertTrue(LibraryCategories.groups(listOf(AudioBook("empty", "Empty", emptyList())), LibraryCategory.AUTHORS).isEmpty())
        assertTrue(LibraryCategories.matching(library, LibraryCategory.GENRES, "absent").isEmpty())
    }

    @Test
    fun relatedSectionsMatchMetadataAndExcludeCurrentBookAndDuplicates() {
        val book = AudioBook("a", "A", emptyList(), author = "Auteur",
            series = listOf("Série"), genres = listOf("Fantastique"))
        val series = AudioBook("b", "B", emptyList(), series = listOf(" serie "))
        val author = AudioBook("c", "C", emptyList(), author = "AUTEUR")
        val genre = AudioBook("d", "D", emptyList(), genres = listOf("fantastique"))
        val sections = RelatedBooks.sections(book, listOf(book, series, author, genre, genre))
        assertEquals(listOf(listOf("b"), listOf("c"), listOf("d")), sections.map { it.second.map { b -> b.id } })
        assertTrue(RelatedBooks.sections(book, listOf(book)).isEmpty())
    }

    @Test
    fun snapshotUsesCachedMetadataWithoutReadingAudioOrCallingAnApi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "book-metadata-test.db"
        context.deleteDatabase(name)
        try {
            LibraryDatabase(context, name).use { db ->
                val uri = Uri.parse("content://missing/chapter")
                val book = AudioBook("book", "Folder", listOf(AudioTrack("file.mp3", uri)))
                db.saveLibrarySnapshot("tree", listOf(book))
                db.saveBookTags(book.id, CachedBookTags(
                    signature = uri.toString(),
                    title = null, author = null, artworkPath = null,
                    trackTitles = listOf("Chapitre"),
                    openLibraryAuthor = "Auteur",
                    openLibraryTitle = "Titre récupéré",
                    series = listOf("Série"), genres = listOf("Roman"),
                ))
                val restored = db.librarySnapshot("tree")!!.single()
                assertEquals("Titre récupéré", restored.title)
                assertEquals("Auteur", restored.author)
                assertEquals("Chapitre", restored.tracks.single().name)
                assertTrue(restored.hasOpenLibraryMetadata)
                assertEquals(listOf("Série"), restored.series)
                assertEquals(listOf("Roman"), restored.genres)
                db.saveBookTags(book.id, db.bookTags(book.id, uri.toString())!!.copy(
                    title = "Titre local", author = "Auteur local",
                ))
                assertEquals("Titre local", db.librarySnapshot("tree")!!.single().title)
                assertEquals("Auteur local", db.librarySnapshot("tree")!!.single().author)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
