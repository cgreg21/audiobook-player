package org.nativescript.audiobookplayer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DemoScreenshotsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: MainActivity
    private lateinit var books: List<AudioBook>

    @Test
    fun captureDemoScreens() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("demoScreenshots") == "1")
        check(context.packageName.endsWith(".debug") && Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Demo data may only be installed in the debug application on an emulator"
        }
        seed()
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        repeat(50) {
            if (controller() == null) Thread.sleep(100)
        }
        assertNotNull("Media controller must be connected", controller())

        capture("01-bibliotheque")
        show("renderLibraryRows", books)
        capture("02-tous-les-livres")
        show("renderLibraryBrowse")
        capture("03-parcourir")
        listOf(
            LibraryCategory.AUTHORS to "04-auteurs",
            LibraryCategory.GENRES to "05-genres",
            LibraryCategory.SERIES to "06-series",
        ).forEach { (category, name) ->
            show("renderLibraryCategory", category)
            capture(name)
        }
        show("renderLibrarySelection", LibraryCategory.SERIES, "Les Horizons")
        capture("07-selection-serie")
        show("renderBookDetail", books.first())
        capture("08-detail-livre")
        scrollToBottom()
        capture("09-livres-associes")
        show("renderBookDetail", books.first())
        show("showBookInformation", books.first())
        capture("10-informations-livre")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(300)

        show("playBook", books.first(), null)
        Thread.sleep(800)
        instrumentation.runOnMainSync {
            controller()!!.pause()
            controller()!!.seekTo(2, 45_000)
        }
        show("renderPlayer")
        capture("11-lecteur")
        show("openChapterDrawer", false)
        capture("12-playlist")
        show("closeChapterDrawer")
        show("renderBookDetail", books.first())
        show("openChapterDrawer", false)
        capture("13-chapitres-livre")
        show("closeChapterDrawer")

        show("renderPodcastsHome")
        capture("14-podcasts")
        show("renderPodcastDetail", "https://demo.invalid/sciences")
        capture("15-detail-podcast")
        scrollToBottom()
        capture("16-episodes")
        show("renderDownloads")
        capture("17-telechargements")
        show("renderSettings")
        capture("18-options")
        scrollToBottom()
        capture("19-stockage-reseau")

        show("stopPlayback")
        capture("20-lecteur-vide")
        show("showEmptyLibrary")
        capture("21-import-dossier")
        show("renderLibraryHome", books)
    }

    private fun seed() {
        val directory = File(context.filesDir, "demo").apply { mkdirs() }
        val audio = File(directory, "silence.wav")
        val dataSize = 8_000 * 2 * 600
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
        }
        audio.outputStream().use { output ->
            output.write(header.array())
            val silence = ByteArray(16_000)
            repeat(600) { output.write(silence) }
        }
        val titles = listOf("Le Voyage des brumes", "La Cite des etoiles", "Le Jardin du temps",
            "Les Marees lointaines", "Cartes du ciel", "Un matin sur Mars")
        val authors = listOf("Camille Moreau", "Camille Moreau", "Alex Robin",
            "Nora Lenoir", "Alex Robin", "Nora Lenoir")
        val colors = listOf("#7056b5", "#246c91", "#367e65", "#b66c45", "#525ca1", "#a14c72")
        books = titles.mapIndexed { index, title ->
            val cover = File(directory, "book-$index.png")
            drawCover(cover, title, authors[index], colors[index], "LIVRE AUDIO")
            AudioBook(
                id = "demo|book-$index", title = title, author = authors[index],
                artworkPath = cover.path,
                tracks = (1..8).map { AudioTrack("Chapitre $it - ${listOf("Le depart", "La rencontre", "L'horizon", "Les chemins")[ (it - 1) % 4 ]}", Uri.fromFile(audio)) },
                series = if (index < 2) listOf("Les Horizons") else listOf("Recits imaginaires"),
                genres = if (index % 2 == 0) listOf("Aventure", "Imaginaire") else listOf("Science-fiction"),
            )
        }
        LibraryDatabase(context).use { db ->
            db.setSetting("feeds.interval", "0")
            db.setSetting("theme.mode", "dark")
            db.setSetting("theme.palette", "violet")
            db.setSetting("downloads.mobile", "0")
            db.saveTreeUri("demo-library")
            db.saveLibrarySnapshot("demo-library", books)
            db.setBookRead(books[2].id, 8, true)
            db.markChapterRead(books.first().id, 0)
            db.markChapterRead(books.first().id, 1)
            db.saveProgress(books.first().id, 2, 45_000)
            listOf("sciences" to "Demain, les sciences", "voyages" to "Les Escales imaginaires").forEachIndexed { index, (slug, title) ->
                val address = "https://demo.invalid/$slug.png"
                val cache = File(context.cacheDir, "podcast-covers").apply { mkdirs() }
                val hash = MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                drawCover(File(cache, hash), title, "Studio Demo", colors[index + 2], "PODCAST")
                db.savePodcast(SavedPodcast(
                    feedUrl = "https://demo.invalid/$slug", title = title, author = "Studio Demo",
                    artwork = address,
                    description = "Un rendez-vous pour explorer des idees et des histoires. Une collection fictive creee pour presenter les fonctions de l'application.",
                    episodes = (1..5).map { number ->
                        SavedEpisode(
                            id = "episode-$number", title = listOf("Aux frontieres du possible", "Un monde a decouvrir", "La grande traversee", "Questions d'avenir", "Le prochain chapitre")[number - 1],
                            remoteUrl = "https://demo.invalid/$slug/$number.mp3",
                            localPath = if (number == 1) audio.path else null,
                            description = "Dans cet episode de demonstration, nous explorons une nouvelle perspective et partageons les etapes de ce voyage imaginaire.",
                            publishedAt = "Fri, 30 Oct 2026 10:00:00 GMT", duration = "00:24:30", artwork = address,
                        )
                    },
                ), index)
            }
            db.saveDownloadQueue(listOf(
                PodcastDownload("https://demo.invalid/sciences|episode-2", "https://demo.invalid/sciences",
                    "episode-2", "Un monde a decouvrir", DownloadStatus.PAUSED, 12_000_000, 30_000_000),
                PodcastDownload("https://demo.invalid/voyages|episode-3", "https://demo.invalid/voyages",
                    "episode-3", "La grande traversee", DownloadStatus.PAUSED, 6_000_000, 24_000_000),
            ))
        }
    }

    private fun drawCover(file: File, title: String, author: String, color: String, label: String) {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor(color))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(45, 255, 255, 255) }
        canvas.drawCircle(520f, 60f, 260f, paint)
        canvas.drawCircle(50f, 590f, 240f, paint)
        paint.color = Color.WHITE
        paint.textSize = 22f
        canvas.drawText(label, 45f, 65f, paint)
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = 54f
        title.split(" ").chunked(2).forEachIndexed { index, words ->
            canvas.drawText(words.joinToString(" "), 45f, 240f + index * 65, paint)
        }
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = 26f
        canvas.drawText(author, 45f, 535f, paint)
        paint.textSize = 18f
        canvas.drawText("DEMONSTRATION", 45f, 575f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun controller(): MediaController? {
        val field = MainActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
        return field.get(activity) as? MediaController
    }

    private fun show(name: String, vararg arguments: Any?) {
        instrumentation.runOnMainSync {
            val method = MainActivity::class.java.declaredMethods.single {
                it.name == name && it.parameterCount == arguments.size
            }
            method.isAccessible = true
            method.invoke(activity, *arguments)
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
    }

    private fun scrollToBottom() {
        fun find(view: View): ScrollView? {
            if (view is ScrollView) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        instrumentation.runOnMainSync {
            checkNotNull(find(activity.window.decorView)).fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(600)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
