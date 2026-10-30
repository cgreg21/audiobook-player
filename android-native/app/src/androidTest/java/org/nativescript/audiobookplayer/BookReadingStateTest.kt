package org.nativescript.audiobookplayer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookReadingStateTest {
    @Test
    fun bulkStatesMatchIndividualQueriesAndReflectLaterUpdates() {
        withDatabase { db ->
            val ids = listOf("unread", "started", "read", "chapters", "index", "quoted'book")
            db.saveProgress("started", 0, 500)
            db.saveProgress("index", 1, 0)
            db.saveProgress("quoted'book", 0, 100)
            db.setBookRead("read", 2, true)
            db.markChapterRead("chapters", 0)
            db.saveProgress("unrelated", 3, 1000)
            val states = db.readingStates(ids + "read")
            assertEquals(ids.toSet(), states.keys)
            ids.forEach { id ->
                assertEquals(db.progress(id), states.getValue(id).progress)
                assertEquals(db.readChapters(id).size, states.getValue(id).readChapterCount)
            }
            val tracks = listOf(AudioTrack("One", Uri.EMPTY), AudioTrack("Two", Uri.EMPTY))
            ids.forEach { id ->
                val book = AudioBook(id, id, tracks)
                assertEquals(id == "read", states.getValue(id).isRead(book))
                assertEquals(id in listOf("started", "chapters", "index", "quoted'book"),
                    states.getValue(id).isInProgress(book))
            }
            val empty = AudioBook("empty", "Empty", emptyList())
            assertFalse(BookReadingState().isRead(empty))
            assertFalse(BookReadingState().isInProgress(empty))
            db.setBookRead("read", 2, false)
            db.saveProgress("started", 1, 900)
            val updated = db.readingStates(ids)
            assertEquals(0, updated.getValue("read").readChapterCount)
            assertEquals(db.progress("started"), updated.getValue("started").progress)
            assertTrue(db.readingStates(emptyList()).isEmpty())
        }
    }

    @Test
    fun bulkStatesSupportMoreThanSqliteParameterLimit() {
        withDatabase { db ->
            val ids = (0..1000).map { "book-$it" }
            val sql = db.writableDatabase
            sql.beginTransaction()
            try {
                ids.forEach { id ->
                    db.saveProgress(id, 1, 100)
                    db.markChapterRead(id, 0)
                }
                sql.setTransactionSuccessful()
            } finally {
                sql.endTransaction()
            }
            val states = db.readingStates(ids)
            assertEquals(ids.size, states.size)
            ids.forEach { id ->
                assertEquals(db.progress(id), states.getValue(id).progress)
                assertEquals(1, states.getValue(id).readChapterCount)
            }
        }
    }

    private fun withDatabase(test: (LibraryDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "book-reading-state-test.db"
        context.deleteDatabase(name)
        try {
            LibraryDatabase(context, name).use(test)
        } finally {
            context.deleteDatabase(name)
        }
    }
}
