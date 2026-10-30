package org.nativescript.audiobookplayer

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.animation.ObjectAnimator
import android.os.Build
import android.os.Bundle
import android.view.animation.LinearInterpolator
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private lateinit var database: LibraryDatabase
    private lateinit var podcasts: PodcastRepository
    private lateinit var podcastArtwork: PodcastArtworkLoader
    private lateinit var downloadQueue: PodcastDownloadQueue
    private lateinit var rootView: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var headerTitle: TextView
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniSubtitle: TextView
    private lateinit var miniPlayButton: Button
    private lateinit var miniProgress: android.widget.ProgressBar
    private lateinit var miniArtwork: ImageView
    private data class PlayerArtworkSource(val isLocal: Boolean, val address: String?)
    private var playerArtworkSource: PlayerArtworkSource? = null
    private lateinit var fullPlayerPanel: LinearLayout
    private lateinit var artworkView: ImageView
    private lateinit var vinylSurface: android.widget.FrameLayout
    private lateinit var tonearm: android.widget.FrameLayout
    private lateinit var nowPlaying: TextView
    private lateinit var nowPlayingArtist: TextView
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: TextView
    private lateinit var playbackSpeedButton: TextView
    private lateinit var settingsButton: TextView
    private lateinit var navigationBar: LinearLayout
    private lateinit var chapterDrawerHandle: android.widget.FrameLayout
    private lateinit var screenLayer: android.widget.FrameLayout
    private var chapterDrawer: android.widget.FrameLayout? = null
    private var chapterDrawerPanel: LinearLayout? = null
    private val chapterDrawerBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeChapterDrawer()
    }
    private data class Page(
        val screen: String, val bookId: String? = null, val feedUrl: String? = null,
        val category: LibraryCategory? = null, val categoryValue: String? = null,
    )
    private var browseCategory: LibraryCategory? = null
    private var browseValue: String? = null
    private val pageHistory = mutableListOf<Page>()
    private var navigationReady = false
    private var restoringPage = false
    private val pageBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val page = pageHistory.removeAt(pageHistory.lastIndex)
            restoringPage = true
            try {
                showPage(page)
            } finally {
                restoringPage = false
                isEnabled = pageHistory.isNotEmpty()
            }
        }
    }
    private var chapterDrawerRows: LinearLayout? = null
    private var chapterDrawerTitle: TextView? = null
    private var chapterDrawerSignature: String? = null
    private lateinit var iconTypeface: android.graphics.Typeface
    private val navigationButtons = mutableListOf<Pair<String, TextView>>()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var durationMs = 0L
    private var vinylAnimator: ObjectAnimator? = null
    private var seeking = false
    private var currentScreen = "library"
    private var currentFeedUrl: String? = null
    private var currentBook: AudioBook? = null
    private var metadataLookupInProgress = false
    private var libraryBooks: List<AudioBook> = emptyList()
    @Volatile private var libraryLoadGeneration = 0
    private var libraryQuery = ""
    private var libraryFilter = "all"
    private var librarySort = "title"
    private var themeMode = "dark"
    private var paletteName = "violet"
    private var availableUpdate: AppUpdate? = null
    private var updateRunning = false
    private var updateMessage: String? = null
    @Volatile private var refreshingFeeds = false
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val feedRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val periodicFeedRefresh = object : Runnable {
        override fun run() {
            refreshFeedsIfDue()
            feedRefreshHandler.postDelayed(this, 60_000)
        }

    }
    private val refreshTask = object : Runnable {
        override fun run() {
            updatePlayerControls()
            refreshHandler.postDelayed(this, 1_000)
        }
    }

    private val folderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            database.saveTreeUri(uri.toString())
            renderLibrary(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = LibraryDatabase(this)
        themeMode = database.setting("theme.mode", "dark")
        paletteName = database.setting("theme.palette", "violet")
        podcasts = PodcastRepository(this, database)
        podcastArtwork = PodcastArtworkLoader(this)
        downloadQueue = PodcastDownloadQueue.shared(this, onChanged = {
            when (currentScreen) {
                "podcast" -> currentFeedUrl?.let(::renderPodcastDetail)
                "downloads" -> renderDownloads()
            }
        })
        buildScreen()
        connectPlayer()
        database.treeUri()?.let { treeUri ->
            val cachedBooks = try {
                database.librarySnapshot(treeUri)
            } catch (error: Exception) {
                android.util.Log.e("MainActivity", "Unable to load cached library", error)
                null
            }
            if (cachedBooks == null) {
                renderLibrary(Uri.parse(treeUri))
            } else {
                libraryBooks = cachedBooks
                if (cachedBooks.isEmpty()) showEmptyLibrary() else renderLibraryHome(cachedBooks)
            }
        }
            ?: showEmptyLibrary()
        feedRefreshHandler.postDelayed({ refreshFeedsIfDue(force = true) }, 1_500)
        feedRefreshHandler.postDelayed(periodicFeedRefresh, 60_000)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4202)
        }
        navigationReady = true
    }

    private fun enterPage(
        screen: String, bookId: String? = null, feedUrl: String? = null,
        category: LibraryCategory? = null, categoryValue: String? = null,
    ) {
        val previous = Page(
            currentScreen,
            if (currentScreen == "book") currentBook?.id else null,
            if (currentScreen == "podcast") currentFeedUrl else null,
            browseCategory, browseValue,
        )
        val next = Page(screen, bookId, feedUrl, category, categoryValue)
        if (navigationReady && !restoringPage && previous != next) {
            pageHistory += previous
        }
        currentScreen = screen
        currentFeedUrl = feedUrl
        browseCategory = category
        browseValue = categoryValue
        pageBack.isEnabled = pageHistory.isNotEmpty()
        if (screen != "player") closeChapterDrawer()
        rootView.post { updatePlayerControls() }
    }

    private fun showPage(page: Page) {
        when (page.screen) {
            "library-all" -> renderLibraryRows(libraryBooks)
            "library-browse" -> renderLibraryBrowse()
            "library-category" -> renderLibraryCategory(page.category ?: LibraryCategory.AUTHORS)
            "library-selection" -> renderLibrarySelection(page.category ?: LibraryCategory.AUTHORS, page.categoryValue.orEmpty())
            "book" -> {
                val book = libraryBooks.firstOrNull { it.id == page.bookId }
                if (book != null) {
                    renderBookDetail(book)
                } else {
                    showLibrary()
                    status.text = "Ce livre n’est plus disponible dans la bibliothèque."
                }
            }
            "podcasts" -> renderPodcastsHome()
            "podcast" -> page.feedUrl?.let(::renderPodcastDetail)
            "downloads" -> renderDownloads()
            "settings" -> renderSettings()
            "player" -> renderPlayer()
            else -> showLibrary()
        }
    }

    private fun buildScreen() {
        iconTypeface = android.graphics.Typeface.createFromAsset(assets, "phosphor.ttf")
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(15), dp(2), dp(4))
        }
        val headingCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerTitle = TextView(this).apply {
            text = "Ma bibliothèque"
            textSize = 25f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        headingCopy.addView(headerTitle)
        header.addView(headingCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val onlineBadge = TextView(this).apply {
            text = "\uecde"
            typeface = iconTypeface
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(onlineBadge)
        settingsButton = TextView(this).apply {
            text = "\ue270"
            typeface = iconTypeface
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(paletteColors().primaryText))
            background = roundedDrawable(paletteColors().surfaceAlt, 22)
            setOnClickListener { renderSettings() }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        rootView.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(18)
            rightMargin = dp(18)
        })
        status = TextView(this).apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!s.isNullOrBlank()) {
                        android.widget.Toast.makeText(
                            this@MainActivity, s.toString(), android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        val scrollView = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            dividerDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setSize(0, dp(8))
            }
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        content.setPadding(0, dp(2), 0, dp(12))
        scrollView.addView(content)
        rootView.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
            },
        )

        chapterDrawerHandle = android.widget.FrameLayout(this).apply {
            minimumHeight = dp(48)
            visibility = View.GONE
            contentDescription = "Ouvrir la liste de lecture"
            setOnClickListener { openChapterDrawer() }
            installDrawerGesture(this, open = true)
            addView(View(this@MainActivity).apply {
                background = roundedDrawable(paletteColors().secondaryText, 2)
            }, android.widget.FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
        }
        rootView.addView(chapterDrawerHandle)

        miniPlayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setOnClickListener { renderPlayer() }
        }
        val miniMain = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(8), dp(9), dp(8))
            setOnClickListener { renderPlayer() }
        }
        miniArtwork = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedDrawable(paletteColors().surface, 8)
            clipToOutline = true
        }
        miniMain.addView(miniArtwork, LinearLayout.LayoutParams(dp(44), dp(44)))
        val miniCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        miniTitle = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniSubtitle = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        miniCopy.addView(miniTitle)
        miniCopy.addView(miniSubtitle)
        miniMain.addView(miniCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        miniPlayButton = Button(this).apply {
            text = "▶"
            tag = "icon"
            setOnClickListener {
                controller?.let { player ->
                    if (player.isPlaying) player.pause() else player.play()
                    updatePlayerControls()
                }
            }
        }
        miniMain.addView(miniPlayButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        miniPlayer.addView(miniMain)
        miniProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            isIndeterminate = false
        }
        miniPlayer.addView(miniProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        rootView.addView(miniPlayer)

        navigationBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        listOf("\ue758" to "library", "\ue326" to "podcasts", "\ue33c" to "player").forEach { (icon, tab) ->
            val button = TextView(this).apply {
                text = icon
                typeface = iconTypeface
                textSize = 25f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener {
                    when (tab) {
                        "library" -> showLibrary()
                        "podcasts" -> renderPodcastsHome()
                        else -> renderPlayer()
                    }
                }
            }
            navigationButtons += tab to button
            navigationBar.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        rootView.addView(navigationBar)

        fullPlayerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(20))
            visibility = View.GONE
        }
        val turntable = android.widget.FrameLayout(this).apply {
            background = roundedDrawable(paletteColors().background, 16)
        }
        vinylSurface = android.widget.FrameLayout(this).apply {
            background = ovalDrawable("#101014")
            elevation = dp(3).toFloat()
        }
        listOf(164, 136, 108).forEach { size ->
            vinylSurface.addView(
                View(this).apply {
                    background = outlineOvalDrawable("#24242b", 1)
                },
                android.widget.FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER),
            )
        }
        artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ovalDrawable(paletteColors().surfaceAlt)
            setImageResource(android.R.drawable.ic_media_play)
            clipToOutline = true
            elevation = dp(4).toFloat()
        }
        playerArtworkSource = null
        vinylSurface.addView(
            artworkView,
            android.widget.FrameLayout.LayoutParams(dp(70), dp(70), Gravity.CENTER),
        )
        vinylSurface.addView(
            View(this).apply {
                background = ovalDrawable("#e7e2ef")
                elevation = dp(5).toFloat()
            },
            android.widget.FrameLayout.LayoutParams(dp(5), dp(5), Gravity.CENTER),
        )
        turntable.addView(
            vinylSurface,
            android.widget.FrameLayout.LayoutParams(dp(184), dp(184), Gravity.CENTER).apply {
                topMargin = dp(18)
            },
        )
        tonearm = android.widget.FrameLayout(this).apply {
            pivotX = dp(9).toFloat()
            pivotY = 0f
            clipChildren = false
            elevation = dp(5).toFloat()
        }
        tonearm.addView(
            View(this).apply {
                background = roundedDrawable("#c9c4d6", 2)
            },
            android.widget.FrameLayout.LayoutParams(dp(4), dp(74), Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )
        val cartridge = android.widget.FrameLayout(this).apply {
            clipChildren = false
            background = roundedDrawable("#34313d", 4).apply {
                setStroke(dp(1), Color.parseColor("#c9c4d6"))
            }
        }
        cartridge.addView(
            View(this).apply {
                background = roundedDrawable(paletteColors().accent, 1)
            },
            android.widget.FrameLayout.LayoutParams(dp(2), dp(7), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(2)
            },
        )
        cartridge.addView(
            View(this).apply {
                background = roundedDrawable("#f5f1fa", 1)
                elevation = dp(2).toFloat()
            },
            android.widget.FrameLayout.LayoutParams(dp(3), dp(7), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = -dp(4)
            },
        )
        tonearm.addView(
            cartridge,
            android.widget.FrameLayout.LayoutParams(dp(14), dp(13), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(68)
            },
        )
        turntable.addView(
            tonearm,
            android.widget.FrameLayout.LayoutParams(dp(18), dp(90), Gravity.TOP or Gravity.RIGHT).apply {
                topMargin = dp(38)
                rightMargin = dp(32)
            },
        )
        val tonearmPivot = android.widget.FrameLayout(this).apply {
            background = ovalDrawable(paletteColors().surfaceAlt)
            elevation = dp(7).toFloat()
            addView(
                View(this@MainActivity).apply {
                    background = ovalDrawable(paletteColors().accent)
                },
                android.widget.FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER),
            )
        }
        turntable.addView(
            tonearmPivot,
            android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.RIGHT).apply {
                topMargin = dp(28)
                rightMargin = dp(31)
            },
        )
        vinylAnimator = ObjectAnimator.ofFloat(vinylSurface, View.ROTATION, 0f, 360f).apply {
            duration = 1_800L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        fullPlayerPanel.addView(
            turntable,
            LinearLayout.LayoutParams(dp(240), dp(210)).apply { bottomMargin = dp(14) },
        )
        turntable.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePlayerControls()
        }
        nowPlaying = TextView(this).apply {
            text = "Rien en lecture"
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        fullPlayerPanel.addView(nowPlaying)
        nowPlayingArtist = TextView(this).apply {
            text = ""
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        fullPlayerPanel.addView(nowPlayingArtist)
        seekBar = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    seeking = true
                }

                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    controller?.seekTo(bar.progress * 1_000L)
                }
            })
        }
        fullPlayerPanel.addView(seekBar)
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        currentTime = TextView(this).apply { text = "0:00" }
        totalTime = TextView(this).apply { text = "–:––"; gravity = Gravity.END }
        times.addView(currentTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(totalTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fullPlayerPanel.addView(times)
        fun playerControl(icon: String, description: String, action: () -> Unit): TextView =
            TextView(this).apply {
                text = icon
                typeface = iconTypeface
                textSize = 30f
                gravity = Gravity.CENTER
                contentDescription = description
                setOnClickListener { action() }
            }

        playPauseButton = TextView(this).apply {
            text = "\ue3d0"
            typeface = iconTypeface
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedDrawable(paletteColors().accent, 34)
            contentDescription = "Lecture ou pause"
            setOnClickListener {
                controller?.let { player ->
                    if (player.isPlaying) player.pause() else player.play()
                    updatePlayerControls()
                }
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        fun addControl(view: View) {
            controls.addView(view, LinearLayout.LayoutParams(0, dp(60), 1f))
        }
        addControl(playerControl("\ue5a4", "Chapitre précédent") { skipTrack(-1) })
        addControl(playerControl("\ue6a8", "Reculer de 15 secondes") {
            controller?.let { it.seekTo((it.currentPosition - 15_000).coerceAtLeast(0)) }
        })
        controls.addView(playPauseButton, LinearLayout.LayoutParams(dp(68), dp(68)).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })
        addControl(playerControl("\ue6a6", "Avancer de 30 secondes") {
            controller?.let { player ->
                val target = player.currentPosition + 30_000
                player.seekTo(if (player.duration > 0) target.coerceAtMost(player.duration) else target)
            }
        })
        addControl(playerControl("\ue5a6", "Chapitre suivant") { skipTrack(1) })
        fullPlayerPanel.addView(controls)
        playbackSpeedButton = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setTextColor(Color.parseColor(paletteColors().accent))
            setOnClickListener { cyclePlaybackSpeed() }
        }
        fullPlayerPanel.addView(playbackSpeedButton)
        fullPlayerPanel.addView(Button(this).apply {
            text = "■  Arrêter et fermer le livre"
            contentDescription = "Arrêter la lecture et vider la liste de lecture"
            setOnClickListener { stopPlayback() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        content.addView(fullPlayerPanel)

        screenLayer = android.widget.FrameLayout(this).apply {
            addView(this@MainActivity.rootView, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        setContentView(screenLayer)
        onBackPressedDispatcher.addCallback(this, pageBack)
        onBackPressedDispatcher.addCallback(this, chapterDrawerBack)
        installThemeListeners(rootView)
        applyTheme(rootView)
        updateNavigationStatusBadge(onlineBadge)
    }

    private fun stopPlayback() {
        val player = controller ?: return
        val mediaId = player.currentMediaItem?.mediaId
        val separator = mediaId?.lastIndexOf('#') ?: -1
        if (mediaId != null && separator > 0) {
            mediaId.substring(separator + 1).toIntOrNull()?.let { index ->
                database.saveProgress(
                    mediaId.substring(0, separator), index, player.currentPosition.coerceAtLeast(0),
                )
            }
        }
        player.stop()
        player.clearMediaItems()
        closeChapterDrawer()
        currentBook = null
        vinylAnimator?.cancel()
        vinylSurface.rotation = 0f
        playerArtworkSource = null
        miniArtwork.tag = null
        artworkView.tag = null
        artworkView.setImageResource(android.R.drawable.ic_media_play)
        renderPlayer()
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get()
                controller?.setPlaybackSpeed(playbackSpeed())
                updatePlayerControls()
            } catch (error: Exception) {
                status.text = "Le lecteur audio n’a pas pu démarrer."
            }
        }, mainExecutor)
    }

    private fun showEmptyLibrary() {
        enterPage("library")
        currentFeedUrl = null
        headerTitle.text = "Ma bibliothèque"
        status.text = ""
        content.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(28), dp(24), dp(24))
            background = roundedDrawable(paletteColors().surface, 22)
        }
        card.addView(TextView(this).apply {
            text = "♫"
            textSize = 42f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(paletteColors().accent))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)))
        card.addView(TextView(this).apply {
            text = "Votre bibliothèque est vide"
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "Importez un dossier de livres audio pour les retrouver ici."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })
        card.addView(Button(this).apply {
            text = "＋  Ajouter un dossier"
            setOnClickListener { folderPicker.launch(null) }
        })
        content.addView(card)
    }

    private fun renderLibrary(treeUri: Uri) {
        enterPage("library")
        currentFeedUrl = null
        headerTitle.text = "Ma bibliothèque"
        content.removeAllViews()
        status.text = "Lecture de la bibliothèque…"
        val generation = ++libraryLoadGeneration
        Thread {
            val scanned = try {
                AudioLibrary(this).scan(treeUri)
            } catch (error: Exception) {
                runOnUiThread {
                    if (generation == libraryLoadGeneration) {
                        status.text = "Impossible de lire ce dossier : ${error.localizedMessage ?: "erreur inconnue"}"
                    }
                }
                return@Thread
            }
            try {
                database.saveLibrarySnapshot(treeUri.toString(), scanned)
            } catch (error: Exception) {
                android.util.Log.e("MainActivity", "Unable to save scanned library", error)
            }

            runOnUiThread {
                if (generation != libraryLoadGeneration) return@runOnUiThread
                libraryBooks = scanned
                playerArtworkSource = null
                if (currentScreen == "library") {
                    if (scanned.isEmpty()) {
                        showEmptyLibrary()
                    } else {
                        renderLibraryHome(scanned)
                        status.text = "Chargement des détails des livres…"
                    }
                }
            }
            if (scanned.isEmpty()) return@Thread

            try {
                val books = BookMetadataRepository(this, database).apply(scanned)
                database.saveLibrarySnapshot(treeUri.toString(), books)
                runOnUiThread {
                    if (generation != libraryLoadGeneration) return@runOnUiThread
                    libraryBooks = books
                    playerArtworkSource = null
                    when (currentScreen) {
                        "library" -> renderLibraryHome(books)
                        "library-all" -> renderLibraryRows(books)
                        "book" -> currentBook?.let { selected ->
                            books.firstOrNull { it.id == selected.id }?.let { updated ->
                                currentBook = updated
                                renderBookDetail(updated)
                            }
                        }
                    }
                    if (currentScreen in setOf("library", "library-all", "book")) {
                        status.text = ""
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (generation == libraryLoadGeneration &&
                        currentScreen in setOf("library", "library-all", "book")
                    ) {
                        status.text = "La bibliothèque est disponible, mais ses détails n’ont pas pu être chargés : " +
                            (error.localizedMessage ?: "erreur inconnue")
                    }
                }
            }
        }.start()
    }

    private fun showLibrary() {
        val treeUri = database.treeUri()
        if (treeUri == null) {
            showEmptyLibrary()
            return
        }
        val cachedBooks = try {
            database.librarySnapshot(treeUri)
        } catch (error: Exception) {
            android.util.Log.e("MainActivity", "Unable to load cached library", error)
            null
        }
        if (cachedBooks == null) {
            renderLibrary(Uri.parse(treeUri))
        } else {
            libraryBooks = cachedBooks
            if (cachedBooks.isEmpty()) showEmptyLibrary() else renderLibraryHome(cachedBooks)
        }
    }

    private fun renderLibraryHome(books: List<AudioBook>) {
        enterPage("library")
        headerTitle.text = "Ma bibliothèque"
        content.removeAllViews()
        val lastPlayed = database.lastPlayed()
        val continueBook = books.firstOrNull { it.id == lastPlayed?.bookId }
        if (continueBook != null && lastPlayed != null &&
            (lastPlayed.positionMs > 0 || lastPlayed.trackIndex > 0) &&
            database.readChapters(continueBook.id).size < continueBook.tracks.size
        ) {
            addSectionTitle("CONTINUER LA LECTURE")
            val savedTrack = continueBook.tracks.getOrNull(lastPlayed.trackIndex)?.name.orEmpty()
            val card = horizontalCard()
            card.addView(coverTile(continueBook), LinearLayout.LayoutParams(dp(56), dp(56)))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            copy.addView(TextView(this).apply {
                text = continueBook.title
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            copy.addView(TextView(this).apply {
                text = savedTrack
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            card.addView(Button(this).apply {
                text = "▶"
                tag = "icon"
                setOnClickListener { playBook(continueBook) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            content.addView(card)
        }
        val heading = horizontalCard(transparent = true)
        heading.addView(TextView(this).apply {
            text = "Ajoutés récemment"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(Button(this).apply {
            text = "Voir tous"
            setOnClickListener { renderLibraryRows(books) }
        })
        content.addView(heading)
        content.addView(Button(this).apply {
            text = "Parcourir la bibliothèque"
            setOnClickListener { renderLibraryBrowse() }
        })
        content.addView(TextView(this).apply {
            text = "⟳ Actualiser la bibliothèque"
            textSize = 12f
            gravity = Gravity.END
            setPadding(0, dp(4), dp(4), dp(8))
            setTextColor(Color.parseColor(paletteColors().accent))
            setOnClickListener {
                database.treeUri()?.let { renderLibrary(Uri.parse(it)) } ?: showEmptyLibrary()
            }
        })
        books.take(6).forEach { book -> content.addView(bookRow(book)) }
    }

    private fun renderLibraryBrowse() {
        enterPage("library-browse")
        headerTitle.text = "Parcourir"
        status.text = ""
        content.removeAllViews()
        addBrowseRow("Tous les livres", libraryBooks.size) { renderLibraryRows(libraryBooks) }
        listOf(LibraryCategory.AUTHORS to "Par auteurs",
            LibraryCategory.GENRES to "Par genres", LibraryCategory.SERIES to "Par séries").forEach { (category, title) ->
            addBrowseRow(title, LibraryCategories.groups(libraryBooks, category).size) {
                renderLibraryCategory(category)
            }
        }
    }

    private fun addBrowseRow(title: String, count: Int, action: () -> Unit) {
        content.addView(horizontalCard().apply {
            minimumHeight = dp(64)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "$count  ›"
                textSize = 14f
                setPadding(dp(12), 0, 0, 0)
            })
            setOnClickListener { action() }
        })
    }

    private fun renderLibraryCategory(category: LibraryCategory) {
        enterPage("library-category", category = category)
        headerTitle.text = when (category) {
            LibraryCategory.AUTHORS -> "Auteurs"
            LibraryCategory.GENRES -> "Genres"
            LibraryCategory.SERIES -> "Séries"
        }
        status.text = ""
        content.removeAllViews()
        val groups = LibraryCategories.groups(libraryBooks, category)
        if (groups.isEmpty()) content.addView(TextView(this).apply {
            text = "Aucune métadonnée disponible pour cette catégorie. Complétez les informations des livres."
            textSize = 14f
        })
        groups.forEach { (label, books) ->
            addBrowseRow(label, books.size) { renderLibrarySelection(category, label) }
        }
    }

    private fun renderLibrarySelection(category: LibraryCategory, value: String) {
        enterPage("library-selection", category = category, categoryValue = value)
        headerTitle.text = value
        status.text = ""
        content.removeAllViews()
        val books = LibraryCategories.matching(libraryBooks, category, value)
        addSectionTitle("${books.size} livre${if (books.size > 1) "s" else ""}")
        books.forEach { content.addView(bookRow(it)) }
    }

    private fun renderLibraryRows(books: List<AudioBook>) {
        enterPage("library-all")
        headerTitle.text = "Tous (${books.size})"
        content.removeAllViews()
        content.addView(Button(this).apply {
            text = "‹  Bibliothèque"
            setOnClickListener { renderLibraryHome(books) }
        })
        content.addView(TextView(this).apply {
            text = "⟳ Actualiser la bibliothèque"
            textSize = 12f
            gravity = Gravity.END
            setPadding(0, dp(4), dp(4), dp(8))
            setTextColor(Color.parseColor(paletteColors().accent))
            setOnClickListener {
                database.treeUri()?.let { renderLibrary(Uri.parse(it)) } ?: showEmptyLibrary()
            }
        })
        val search = EditText(this).apply {
            hint = "Rechercher dans la bibliothèque"
            setSingleLine(true)
            setText(libraryQuery)
        }
        content.addView(search)
        val filters = listOf("all" to "Tout", "unread" to "Non lus", "read" to "Lus", "progress" to "En cours")
        filters.chunked(2).forEach { filtersInRow ->
            val filterRow = actionRow()
            filtersInRow.forEach { (key, label) ->
                filterRow.addView(Button(this).apply {
                    text = if (libraryFilter == key) "• $label" else label
                    setOnClickListener {
                        libraryFilter = key
                        renderLibraryRows(books)
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            content.addView(filterRow)
        }
        content.addView(Button(this).apply {
            text = if (librarySort == "title") "Tri : titre (A–Z)" else "Tri : récemment écoutés"
            setOnClickListener {
                librarySort = if (librarySort == "title") "recent" else "title"
                renderLibraryRows(books)
            }
        })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(rows)
        fun applyFilter() {
            libraryQuery = search.text.toString()
            val query = libraryQuery.trim()
            val readingStates = database.readingStates(books.map { it.id })
            val filtered = books.filter { book ->
                val state = readingStates.getValue(book.id)
                val matchesFilter = when (libraryFilter) {
                    "read" -> state.isRead(book)
                    "unread" -> !state.isRead(book)
                    "progress" -> state.isInProgress(book)
                    else -> true
                }
                val matchesSearch = query.isEmpty() ||
                    book.title.contains(query, ignoreCase = true) ||
                    book.author.orEmpty().contains(query, ignoreCase = true) ||
                    book.tracks.any { it.name.contains(query, ignoreCase = true) }
                matchesFilter && matchesSearch
            }.let { matching ->
                if (librarySort == "recent") {
                    matching.sortedByDescending { readingStates.getValue(it.id).progress.updatedAt }
                } else {
                    matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                }
            }
            rows.removeAllViews()
            filtered.forEach { book -> rows.addView(bookRow(book)) }
            if (filtered.isEmpty()) {
                rows.addView(TextView(this).apply { text = "Aucun livre correspondant." })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        applyFilter()
    }

    private fun bookRow(book: AudioBook): View {
        val row = horizontalCard()
        row.addView(coverTile(book), LinearLayout.LayoutParams(dp(56), dp(56)))
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        summary.addView(TextView(this).apply {
            text = book.title
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        summary.addView(TextView(this).apply {
            text = book.author?.let { "$it · " }.orEmpty() +
                "${book.tracks.size} fichier${if (book.tracks.size > 1) "s" else ""}"
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { renderBookDetail(book) }
        return row
    }

    private fun coverTile(book: AudioBook): View {
        if (book.artworkPath != null) {
            return ImageView(this).apply {
                setImageURI(Uri.fromFile(java.io.File(book.artworkPath)))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedDrawable(paletteColors().surfaceAlt, 12)
                clipToOutline = true
            }
        }
        return TextView(this).apply {
            text = "♫"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(paletteColors().accent))
            background = roundedDrawable(paletteColors().surfaceAlt, 12)
        }
    }

    private fun horizontalCard(transparent: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (transparent) 0 else dp(12), dp(10), if (transparent) 0 else dp(12), dp(10))
            if (!transparent) background = roundedDrawable(paletteColors().surface, 16)
            if (!transparent) {
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.bottomMargin = dp(9)
                layoutParams = params
            }
        }

    private fun addSectionTitle(title: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(paletteColors().accent))
            setPadding(dp(2), dp(10), 0, dp(8))
        })
    }

    private fun actionRow(): LinearLayout = object : LinearLayout(this) {
        override fun onViewAdded(child: View) {
            super.onViewAdded(child)
            child.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        dividerDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setSize(dp(8), 0)
        }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }

    private fun renderPlayer() {
        enterPage("player")
        currentFeedUrl = null
        headerTitle.text = "À l’écoute"
        status.text = ""
        content.removeAllViews()
        if (controller?.currentMediaItem == null) {
            fullPlayerPanel.visibility = View.GONE
            content.addView(TextView(this).apply {
                text = "Choisissez un livre dans votre bibliothèque pour commencer l’écoute."
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(36), dp(24), dp(12))
            })
            content.addView(Button(this).apply {
                text = "Découvrir ma bibliothèque"
                setOnClickListener { showLibrary() }
            })
        } else {
            fullPlayerPanel.visibility = View.VISIBLE
            content.addView(fullPlayerPanel)
        }
        updatePlayerControls()
    }

    private fun installDrawerGesture(handle: View, open: Boolean) {
        var startY = 0f
        var dragging = false
        var startTranslation = 0f
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    dragging = false
                    chapterDrawerPanel?.animate()?.cancel()
                    startTranslation = chapterDrawerPanel?.translationY ?: 0f
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val distance = event.rawY - startY
                    if (!dragging && kotlin.math.abs(distance) > dp(8) && (!open || distance < 0)) {
                        dragging = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        if (open) openChapterDrawer(animate = false)
                    }
                    if (dragging) {
                        chapterDrawerPanel?.let { panel ->
                            val height = panel.layoutParams.height.toFloat()
                            panel.translationY = ((if (open) height else startTranslation) + distance)
                                .coerceIn(0f, height)
                            chapterDrawer?.getChildAt(0)?.alpha = 1f - panel.translationY / height
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!dragging && event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        view.performClick()
                    } else if (dragging) {
                        chapterDrawerPanel?.let { panel ->
                            val distance = event.rawY - startY
                            val close = if (event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                                open
                            } else if (kotlin.math.abs(distance) > dp(40)) {
                                distance > 0
                            } else {
                                panel.translationY > panel.layoutParams.height / 2f
                            }
                            if (close) closeChapterDrawer() else settleChapterDrawer()
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun settleChapterDrawer() {
        chapterDrawerPanel?.animate()?.translationY(0f)?.setDuration(220)?.start()
        chapterDrawer?.getChildAt(0)?.animate()?.alpha(1f)?.setDuration(220)?.start()
    }

    private fun closeChapterDrawer() {
        val layer = chapterDrawer ?: return
        chapterDrawerPanel?.animate()?.cancel()
        screenLayer.removeView(layer)
        chapterDrawer = null
        chapterDrawerPanel = null
        chapterDrawerRows = null
        chapterDrawerTitle = null
        chapterDrawerSignature = null
        chapterDrawerBack.isEnabled = false
    }

    private fun openChapterDrawer(animate: Boolean = true) {
        val player = controller
        val detailBook = currentBook?.takeIf { currentScreen == "book" }
        if (chapterDrawer != null || (detailBook == null && (player?.mediaItemCount ?: 0) == 0)) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            background = roundedDrawable(paletteColors().surface, 20)
        }
        val handle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = dp(64)
            contentDescription = "Fermer la liste de lecture"
            setOnClickListener { closeChapterDrawer() }
            installDrawerGesture(this, open = false)
        }
        handle.addView(View(this).apply {
            background = roundedDrawable(paletteColors().secondaryText, 2)
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { bottomMargin = dp(12) })
        chapterDrawerTitle = TextView(this).apply {
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(paletteColors().primaryText))
        }
        handle.addView(chapterDrawerTitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        panel.addView(handle)
        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chapterDrawerRows = rows
        scroll.addView(rows)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val layer = android.widget.FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
        }
        val scrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#8c000000"))
            setOnClickListener { closeChapterDrawer() }
        }
        layer.addView(scrim, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val bottomBarTop = rootView.top + if (miniPlayer.visibility == View.VISIBLE) {
            miniPlayer.top
        } else {
            navigationBar.top
        }
        val availableHeight = bottomBarTop.coerceIn(1, screenLayer.height.coerceAtLeast(1))
        val height = (screenLayer.height * 0.65f).toInt()
            .coerceAtLeast(dp(160)).coerceAtMost(availableHeight)
        layer.addView(panel, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM,
        ))
        panel.isClickable = true
        chapterDrawer = layer
        chapterDrawerPanel = panel
        chapterDrawerBack.isEnabled = true
        screenLayer.addView(layer, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply {
            bottomMargin = screenLayer.height - availableHeight
        })
        panel.translationY = height.toFloat()
        scrim.alpha = 0f
        if (animate) {
            settleChapterDrawer()
        }
        updateChapterDrawer()
        val activeIndex = if (detailBook != null) {
            detailBook.tracks.indices.firstOrNull {
                player?.currentMediaItem?.mediaId == "${detailBook.id}#$it"
            } ?: 0
        } else player?.currentMediaItemIndex ?: 0
        val activeRow = rows.getChildAt(activeIndex)
        if (activeRow != null) scroll.post { scroll.smoothScrollTo(0, activeRow.top) }
    }

    private fun updateChapterDrawer() {
        val player = controller
        val detailBook = currentBook?.takeIf { currentScreen == "book" }
        val available = if (detailBook != null) detailBook.tracks.isNotEmpty()
            else currentScreen == "player" && player?.currentMediaItem != null
        chapterDrawerHandle.visibility =
            if (available) View.VISIBLE else View.GONE
        if (!available) {
            closeChapterDrawer()
            return
        }
        val rows = chapterDrawerRows ?: return
        val items = detailBook?.tracks?.mapIndexed { index, track ->
            MediaItem.Builder().setMediaId("${detailBook.id}#$index")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.name).build()).build()
        } ?: player?.let { current ->
            (0 until current.mediaItemCount).map { current.getMediaItemAt(it) }
        }.orEmpty()
        val readByBook = items.map { it.mediaId.substringBeforeLast('#') }.distinct()
            .associateWith { database.readChapters(it) }
        val signature = "${items.map { it.mediaId to it.mediaMetadata.title }}|" +
            "${player?.currentMediaItem?.mediaId}|${player?.isPlaying}|$readByBook"
        if (signature == chapterDrawerSignature) return
        chapterDrawerSignature = signature
        chapterDrawerTitle?.text = "Chapitres · ${detailBook?.title ?: player?.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty()}"
        rows.removeAllViews()
        items.forEachIndexed { index, item ->
            val active = item.mediaId == player?.currentMediaItem?.mediaId
            val chapterIndex = item.mediaId.substringAfterLast('#').toIntOrNull()
            val read = chapterIndex != null &&
                readByBook[item.mediaId.substringBeforeLast('#')]?.contains(chapterIndex) == true
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedDrawable(
                    if (active) paletteColors().surfaceAlt else paletteColors().surface, 12,
                )
                setOnClickListener {
                    if (detailBook != null) {
                        playBook(detailBook, index)
                        closeChapterDrawer()
                        return@setOnClickListener
                    }
                    val current = controller ?: return@setOnClickListener
                    val queueIndex = (0 until current.mediaItemCount)
                        .firstOrNull { current.getMediaItemAt(it).mediaId == item.mediaId }
                    if (queueIndex != null) {
                        if (current.currentMediaItemIndex != queueIndex) current.seekTo(queueIndex, 0L)
                        current.play()
                        closeChapterDrawer()
                        updatePlayerControls()
                    }
                }
            }
            row.addView(TextView(this).apply {
                text = if (active) {
                    if (player?.isPlaying == true) "\ue44a" else "\ue39e"
                } else if (read) "\ue184" else ""
                typeface = iconTypeface
                textSize = 20f
                setTextColor(Color.parseColor(paletteColors().accent))
            }, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = item.mediaMetadata.title
                textSize = 14f
                setTextColor(Color.parseColor(paletteColors().primaryText))
                setPadding(0, 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (active) "En cours" else if (read) "Terminé" else ""
                textSize = 11f
                setTextColor(Color.parseColor(paletteColors().secondaryText))
            })
            rows.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun renderBookDetail(book: AudioBook) {
        enterPage("book", bookId = book.id)
        currentBook = book
        headerTitle.text = book.title
        content.removeAllViews()
        status.text = ""
        content.addView(TextView(this).apply {
            text = "‹  Bibliothèque"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(paletteColors().accent))
            setPadding(0, 0, 0, dp(14))
            setOnClickListener {
                showLibrary()
            }
        })

        if (book.artworkPath != null) {
            content.addView(ImageView(this).apply {
                setImageURI(Uri.fromFile(java.io.File(book.artworkPath)))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedDrawable(paletteColors().surfaceAlt, 16)
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(180), dp(180)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            })
        } else {
            val placeholder = android.widget.FrameLayout(this).apply {
                background = roundedDrawable(paletteColors().surfaceAlt, 16)
                addView(TextView(this@MainActivity).apply {
                    text = "♫"
                    textSize = 46f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(paletteColors().accent))
                }, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
            content.addView(placeholder, LinearLayout.LayoutParams(dp(180), dp(180)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            })
        }

        content.addView(TextView(this).apply {
            text = book.title
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = book.author?.let { "$it · " }.orEmpty() +
                "${book.tracks.size} fichier${if (book.tracks.size > 1) "s" else ""}"
            textSize = 11f
            setTextColor(Color.parseColor(paletteColors().secondaryText))
            setPadding(0, 0, 0, dp(14))
        })

        val saved = database.progress(book.id)
        val playRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            setPadding(0, dp(4), 0, dp(4))
        }
        val playButton = Button(this).apply {
            text = if (saved.positionMs > 0 || saved.trackIndex > 0) "▶  Reprendre" else "▶  Lire"
            tag = "primary"
            setOnClickListener { playBook(book) }
        }
        playRow.addView(playButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })
        if (book.id.contains("|")) {
            playRow.addView(TextView(this).apply {
                text = "\ue88e"
                typeface = iconTypeface
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(paletteColors().accent))
                background = roundedDrawable(paletteColors().surfaceAlt, 12)
                contentDescription = "Informations du livre"
                setOnClickListener { showBookInformation(book) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        content.addView(playRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(16) })

        val read = database.readChapters(book.id)
        content.addView(Button(this).apply {
            text = if (book.tracks.isNotEmpty() && read.size >= book.tracks.size) "Marquer non lu" else "Marquer lu"
            setOnClickListener {
                database.setBookRead(book.id, book.tracks.size, read.size != book.tracks.size)
                renderBookDetail(book)
            }
        })

        RelatedBooks.sections(book, libraryBooks).forEach { (title, books) ->
            addSectionTitle(title)
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedDrawable(paletteColors().surface, 16)
            }
            books.forEach { related ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setOnClickListener { renderBookDetail(related) }
                    contentDescription = "${related.title}, ${related.author.orEmpty()}"
                }
                card.addView(coverTile(related), LinearLayout.LayoutParams(dp(112), dp(112)))
                card.addView(TextView(this).apply {
                    text = related.title
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(8), 0, 0)
                })
                card.addView(TextView(this).apply {
                    text = related.author.orEmpty()
                    textSize = 11f
                    setPadding(0, dp(4), 0, 0)
                })
                block.addView(card, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(12)
                })
            }
            content.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(block)
            })
        }
        updateChapterDrawer()
    }

    private fun showBookInformation(book: AudioBook) {
        val details = buildList {
            book.author?.let { add("Auteur : $it") }
            if (book.series.isNotEmpty()) add("Série : ${book.series.joinToString(", ")}")
            if (book.genres.isNotEmpty()) add("Genres : ${book.genres.joinToString(", ")}")
            add("${book.tracks.size} fichier${if (book.tracks.size > 1) "s" else ""}")
            if (book.hasOpenLibraryMetadata) add("Certaines informations proviennent d’Open Library.")
        }.joinToString("\n")
        val lookupNeeded = book.author.isNullOrBlank() || book.artworkPath == null ||
            book.series.isEmpty() || book.genres.isEmpty()
        val dialog = AlertDialog.Builder(this)
            .setTitle(book.title)
            .setMessage(details)
            .setNegativeButton("Fermer", null)
        if (lookupNeeded) {
            dialog.setPositiveButton("Rechercher sur Open Library") { _, _ ->
                requestOpenLibraryLookup(book)
            }
        } else if (book.hasOpenLibraryMetadata) {
            dialog.setPositiveButton("Open Library") { _, _ ->
                val search = Uri.encode(book.title)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openlibrary.org/search?q=$search")))
            }
        }
        dialog.show()
    }

    private fun requestOpenLibraryLookup(book: AudioBook) {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            status.text = "Une connexion Internet est nécessaire pour Open Library."
            return
        }
        val onMobile = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (onMobile && database.setting("downloads.mobile", "0") != "1") {
            AlertDialog.Builder(this)
                .setTitle("Recherche avec les données mobiles ?")
                .setMessage("Le titre et l’auteur seront envoyés à Open Library. Le réglage actuel limite l’utilisation du réseau mobile au Wi‑Fi.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Rechercher") { _, _ -> searchOpenLibrary(book) }
                .show()
        } else {
            searchOpenLibrary(book)
        }
    }

    private fun searchOpenLibrary(book: AudioBook) {
        if (metadataLookupInProgress) return
        metadataLookupInProgress = true
        status.text = "Recherche sur Open Library…"
        Thread {
            try {
                val matches = OpenLibraryMetadataRepository(this).search(book.title, book.author)
                runOnUiThread {
                    metadataLookupInProgress = false
                    if (currentScreen != "book" || currentBook?.id != book.id) return@runOnUiThread
                    if (matches.isEmpty()) {
                        status.text = "Aucun livre correspondant trouvé sur Open Library."
                        return@runOnUiThread
                    }
                    val labels = matches.map { match ->
                        "${match.title}\n${match.authors.joinToString(", ").ifBlank { "Auteur inconnu" }}"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Choisir le bon livre")
                        .setItems(labels) { _, selected ->
                            applyOpenLibraryMatch(book, matches[selected])
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    metadataLookupInProgress = false
                    if (currentScreen == "book" && currentBook?.id == book.id) {
                        status.text = "Recherche Open Library impossible : ${error.localizedMessage ?: "erreur réseau"}"
                    }
                }
            }
        }.start()
    }

    private fun applyOpenLibraryMatch(book: AudioBook, match: OpenLibraryMatch) {
        status.text = "Enregistrement des informations…"
        Thread {
            try {
                val result = BookMetadataRepository(this, database).applyOpenLibrary(book, match)
                val updated = result.book
                runOnUiThread {
                    metadataLookupInProgress = false
                    libraryBooks = libraryBooks.map { if (it.id == updated.id) updated else it }
                    playerArtworkSource = null
                    val snapshot = libraryBooks
                    database.treeUri()?.let { treeUri ->
                        Thread {
                            try {
                                database.saveLibrarySnapshot(treeUri, snapshot)
                            } catch (error: Exception) {
                                android.util.Log.e("MainActivity", "Unable to update cached library metadata", error)
                            }
                        }.start()
                    }
                    if (currentScreen == "book" && currentBook?.id == updated.id) {
                        renderBookDetail(updated)
                        status.text = result.coverError?.let { "Couverture Open Library indisponible : $it" }
                            ?: if (updated.hasOpenLibraryMetadata) {
                                "Informations Open Library enregistrées."
                            } else {
                                "Aucune nouvelle métadonnée disponible pour ce livre."
                            }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    metadataLookupInProgress = false
                    if (currentScreen == "book" && currentBook?.id == book.id) {
                        status.text = "Enregistrement Open Library impossible : ${error.localizedMessage ?: "erreur inconnue"}"
                    }
                }
            }
        }.start()
    }

    private fun playBook(book: AudioBook, requestedIndex: Int? = null) {
        val player = controller ?: run {
            status.text = "Le lecteur audio démarre encore."
            return
        }
        val saved = database.progress(book.id)
        val startIndex = (requestedIndex ?: saved.trackIndex).coerceIn(0, book.tracks.lastIndex)
        val positionMs = if (startIndex == saved.trackIndex) saved.positionMs else 0L
        val items = book.tracks.mapIndexed { index, track ->
            MediaItem.Builder()
                .setMediaId("${book.id}#$index")
                .setUri(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.name.substringBeforeLast('.'))
                        .setArtist(book.author ?: book.title)
                        .apply {
                            book.artworkPath?.let { setArtworkUri(Uri.fromFile(java.io.File(it))) }
                        }
                        .build(),
                )
                .build()
        }
        player.setMediaItems(items, startIndex, positionMs)
        player.prepare()
        player.play()
        updatePlayerControls()
    }

    private fun renderPodcastsHome() {
        enterPage("podcasts")
        currentFeedUrl = null
        headerTitle.text = "Podcasts"
        content.removeAllViews()
        status.text = ""

        val searchField = EditText(this).apply {
            hint = "Rechercher un podcast Apple"
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        content.addView(searchField)
        content.addView(Button(this).apply {
            text = "Rechercher"
            setOnClickListener { searchPodcasts(searchField.text.toString()) }
        })

        val subscriptions = database.podcasts()
        if (subscriptions.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = "MES ABONNEMENTS"
                textSize = 14f
                setPadding(0, 20, 0, 8)
            })
            content.addView(Button(this).apply {
                text = "Actualiser les flux"
                isEnabled = true
                setOnClickListener { refreshPodcasts(subscriptions.map { it.feedUrl }) }
            })
            content.addView(Button(this).apply {
                text = "Téléchargements (${downloadQueue.items().size})"
                setOnClickListener { renderDownloads() }
            })
            subscriptions.forEach { podcast ->
                content.addView(podcastCard(
                    podcast.title, "${podcast.author} · ${podcast.episodes.size} épisodes", podcast.artwork,
                ).apply {
                    setOnClickListener { renderPodcastDetail(podcast.feedUrl) }
                })
            }
        }
    }

    private fun searchPodcasts(term: String) {
        val query = term.trim()
        if (query.isEmpty()) return
        status.text = "Recherche…"
        Thread {
            try {
                val results = podcasts.search(query)
                runOnUiThread {
                    status.text = if (results.isEmpty()) "Aucun podcast trouvé." else "Résultats de recherche"
                    results.forEach { result ->
                        val subscribed = database.podcast(result.feedUrl) != null
                        content.addView(podcastCard(
                            result.title,
                            "${result.author} · ${if (subscribed) "Déjà abonné" else "S’abonner"}",
                            result.artwork,
                        ).apply {
                            setOnClickListener {
                                if (subscribed) renderPodcastDetail(result.feedUrl) else subscribeToPodcast(result)
                            }
                        })
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Recherche impossible : ${error.localizedMessage ?: "vérifiez votre connexion"}"
                }
            }
        }.start()
    }

    private fun subscribeToPodcast(result: PodcastSearchResult) {
        status.text = "Chargement des épisodes…"
        Thread {
            try {
                podcasts.subscribe(result)
                runOnUiThread { renderPodcastDetail(result.feedUrl) }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Abonnement impossible : ${error.localizedMessage ?: "flux inaccessible"}"
                }
            }
        }.start()
    }

    private fun renderPodcastDetail(feedUrl: String) {
        val podcast = database.podcast(feedUrl) ?: run {
            renderPodcastsHome()
            return
        }
        enterPage("podcast", feedUrl = feedUrl)
        headerTitle.text = podcast.title
        content.removeAllViews()
        status.text = ""
        content.addView(TextView(this).apply {
            text = "‹  Mes podcasts"
            textSize = 13f
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { renderPodcastsHome() }
        })
        content.addView(podcastCover(podcast.artwork), LinearLayout.LayoutParams(dp(180), dp(180)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })
        content.addView(TextView(this).apply {
            text = podcast.title
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "${podcast.author} · ${podcast.episodes.size} épisodes"
            textSize = 13f
        })
        if (podcast.description.isNotBlank()) {
            content.addView(TextView(this).apply {
                text = podcastText(podcast.description)
                textSize = 14f
                maxLines = 5
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = "Description du podcast, toucher pour afficher le texte complet"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(podcast.title)
                        .setMessage(podcastText(podcast.description))
                        .setPositiveButton("Fermer", null)
                        .show()
                }
            })
        }
        if (podcast.episodes.isNotEmpty()) content.addView(Button(this).apply {
            val saved = database.progress(feedUrl)
            text = if (saved.positionMs > 0 || saved.trackIndex > 0) "▶  Reprendre" else "▶  Lire"
            tag = "primary"
            setOnClickListener { playPodcast(podcast) }
        })
        val actions = actionRow()
        actions.addView(Button(this).apply {
            text = "Actualiser"
            setOnClickListener { refreshPodcasts(listOf(feedUrl), feedUrl) }
        })
        actions.addView(Button(this).apply {
            text = "Se désabonner"
            setOnClickListener { unsubscribePodcast(feedUrl) }
        })
        content.addView(actions)
        val queuedDownloads = downloadQueue.items().filter { it.feedUrl == feedUrl }
        if (queuedDownloads.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = "TÉLÉCHARGEMENTS"
                setPadding(0, 16, 0, 4)
            })
            queuedDownloads.forEach { item ->
                val percent = if (item.total > 0) (item.done * 100 / item.total).toInt() else 0
                content.addView(TextView(this).apply {
                    text = "${item.title} · ${when (item.status) {
                        DownloadStatus.QUEUED -> "En attente"
                        DownloadStatus.DOWNLOADING -> "$percent %"
                        DownloadStatus.PAUSED -> "En pause · $percent %"
                        DownloadStatus.WAITING_FOR_WIFI -> "En attente du Wi-Fi"
                        DownloadStatus.ERROR -> item.error ?: "Erreur"
                    }}"
                    setPadding(0, 8, 0, 4)
                })
                val controls = actionRow()
                if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                    controls.addView(Button(this).apply {
                        text = "Pause"
                        setOnClickListener { downloadQueue.pause(item.key) }
                    })
                } else if (item.status != DownloadStatus.WAITING_FOR_WIFI) {
                    controls.addView(Button(this).apply {
                        text = "Reprendre"
                        setOnClickListener { downloadQueue.resume(item.key) }
                    })
                }
                controls.addView(Button(this).apply {
                    text = "Annuler"
                    setOnClickListener { downloadQueue.cancel(item.key) }
                })
                content.addView(controls)
            }
        }
        if (podcast.episodes.isEmpty()) {
            content.addView(TextView(this).apply { text = "Aucun épisode disponible." })
            return
        }
        addSectionTitle("ÉPISODES")
        val read = database.readChapters(feedUrl)
        podcast.episodes.forEachIndexed { index, episode ->
            val active = controller?.currentMediaItem?.mediaId == "$feedUrl#$index"
            val episodeCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedDrawable(
                    if (active) paletteColors().surfaceAlt else paletteColors().surface, 14,
                )
            }
            episodeCard.addView(podcastCard(
                episode.title,
                listOf(
                    podcastDate(episode.publishedAt),
                    episode.duration.takeIf { it.isNotBlank() }?.let {
                        val seconds = it.toLongOrNull()
                        val duration = if (seconds != null && seconds in 0..Long.MAX_VALUE / 1000) {
                            formatTime(seconds * 1000)
                        } else it
                        "Durée : $duration"
                    }.orEmpty(),
                    if (active) "En cours" else if (index in read) "✓ Terminé" else "",
                    if (episode.localPath != null) "Téléchargé" else "Streaming",
                ).filter(String::isNotBlank).joinToString(" · "),
                episode.artwork ?: podcast.artwork,
            ).apply {
                setOnClickListener { playPodcast(podcast, index) }
            })
            if (episode.description.isNotBlank()) {
                episodeCard.addView(TextView(this).apply {
                    text = podcastText(episode.description)
                    textSize = 13f
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(8), 0, dp(8))
                    contentDescription = "Résumé de l’épisode, toucher pour afficher le texte complet"
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(episode.title)
                            .setMessage(podcastText(episode.description))
                            .setPositiveButton("Fermer", null)
                            .show()
                    }
                })
            }
            val episodeActions = actionRow()
            episodeActions.addView(Button(this).apply {
                text = "▶  Lire"
                setOnClickListener { playPodcast(podcast, index) }
            })
            episodeActions.addView(Button(this).apply {
                text = if (episode.localPath != null) "Supprimer le téléchargement" else "Télécharger"
                setOnClickListener {
                    if (episode.localPath != null) {
                        downloadQueue.removeDownload(feedUrl, episode.id)
                    } else {
                        requestEpisodeDownload(feedUrl, episode.id)
                    }
                }
            })
            episodeCard.addView(episodeActions)
            content.addView(episodeCard)
        }
    }

    private fun podcastText(value: String): CharSequence =
        android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()

    private fun podcastDate(value: String): String {
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
            .parse(value, java.text.ParsePosition(0)) ?: return value
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.FRANCE).format(date)
    }

    private fun podcastCover(address: String?): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundedDrawable(paletteColors().surfaceAlt, 12)
        clipToOutline = true
        podcastArtwork.load(this, address, !isMobileNetwork() || database.setting("downloads.mobile", "0") == "1")
    }

    private fun podcastCard(title: String, subtitle: String, artwork: String?): LinearLayout {
        val row = horizontalCard()
        row.addView(podcastCover(artwork), LinearLayout.LayoutParams(dp(56), dp(56)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        copy.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        copy.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun refreshPodcasts(feedUrls: List<String>, openFeedUrl: String? = null) {
        status.text = "Actualisation des podcasts…"
        Thread {
            val failures = mutableListOf<String>()
            feedUrls.forEach { feedUrl ->
                try {
                    podcasts.refresh(feedUrl)
                } catch (_: Exception) {
                    failures += database.podcast(feedUrl)?.title ?: feedUrl
                }
            }
            database.setSetting("feeds.last", System.currentTimeMillis().toString())
            runOnUiThread {
                if (openFeedUrl != null) {
                    renderPodcastDetail(openFeedUrl)
                    if (failures.isNotEmpty()) status.text = "Actualisation impossible pour ce flux."
                } else {
                    renderPodcastsHome()
                    if (failures.isNotEmpty()) {
                        status.text = "Impossible d’actualiser ${failures.size} flux."
                    }
                }
            }
        }.start()
    }

    private fun refreshFeedsIfDue(force: Boolean = false) {
        val intervalMinutes = database.setting("feeds.interval", "360").toLongOrNull() ?: 360L
        val lastRefresh = database.setting("feeds.last", "0").toLongOrNull() ?: 0L
        if (!force && (intervalMinutes <= 0 || System.currentTimeMillis() - lastRefresh < intervalMinutes * 60_000)) {
            return
        }
        val feeds = database.podcasts().map { it.feedUrl }
        if (feeds.isEmpty() || refreshingFeeds) return
        refreshingFeeds = true
        Thread {
            feeds.forEach { feedUrl ->
                try {
                    podcasts.refresh(feedUrl)
                } catch (_: Exception) {
                    // Keep the last successfully cached episode list for an unavailable feed.
                }
            }
            database.setSetting("feeds.last", System.currentTimeMillis().toString())
            refreshingFeeds = false
            runOnUiThread {
                when (currentScreen) {
                    "podcasts" -> renderPodcastsHome()
                    "podcast" -> currentFeedUrl?.let(::renderPodcastDetail)
                }
            }
        }.start()
    }

    private fun requestEpisodeDownload(feedUrl: String, episodeId: String) {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = connectivity.getNetworkCapabilities(network)
        val onMobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        if (onMobile && database.setting("downloads.mobile", "0") != "1") {
            AlertDialog.Builder(this)
                .setTitle("Télécharger avec les données mobiles ?")
                .setMessage("Le réglage actuel autorise les téléchargements uniquement en Wi-Fi.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Télécharger") { _, _ ->
                    downloadQueue.enqueue(feedUrl, episodeId, mobileAllowed = true)
                }
                .show()
        } else {
            downloadQueue.enqueue(feedUrl, episodeId)
        }
    }

    private fun renderDownloads() {
        enterPage("downloads")
        currentFeedUrl = null
        headerTitle.text = "Téléchargements"
        content.removeAllViews()
        status.text = ""
        content.addView(Button(this).apply {
            text = "← Podcasts"
            setOnClickListener { renderPodcastsHome() }
        })
        val items = downloadQueue.items()
        if (items.isEmpty()) {
            content.addView(TextView(this).apply { text = "Aucun téléchargement en cours." })
            return
        }
        val controls = actionRow()
        controls.addView(Button(this).apply {
            text = "Tout reprendre"
            setOnClickListener { downloadQueue.items().forEach { downloadQueue.resume(it.key) } }
        })
        controls.addView(Button(this).apply {
            text = "Tout annuler"
            setOnClickListener { downloadQueue.cancelAll() }
        })
        content.addView(controls)
        items.forEach { item ->
            val percent = if (item.total > 0) (item.done * 100 / item.total).toInt() else 0
            content.addView(TextView(this).apply {
                text = "${item.title}\n${when (item.status) {
                    DownloadStatus.QUEUED -> "En attente"
                    DownloadStatus.DOWNLOADING -> "$percent %"
                    DownloadStatus.PAUSED -> "En pause · $percent %"
                    DownloadStatus.WAITING_FOR_WIFI -> "En attente du Wi-Fi"
                    DownloadStatus.ERROR -> item.error ?: "Erreur"
                }}"
                setPadding(0, 12, 0, 4)
            })
            val row = actionRow()
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                row.addView(Button(this).apply {
                    text = "Pause"
                    setOnClickListener { downloadQueue.pause(item.key) }
                })
            } else if (item.status != DownloadStatus.WAITING_FOR_WIFI) {
                row.addView(Button(this).apply {
                    text = "Reprendre"
                    setOnClickListener { downloadQueue.resume(item.key) }
                })
            }
            row.addView(Button(this).apply {
                text = "Annuler"
                setOnClickListener { downloadQueue.cancel(item.key) }
            })
            content.addView(row)
        }
    }

    private fun renderSettings() {
        enterPage("settings")
        currentFeedUrl = null
        headerTitle.text = "Options"
        content.removeAllViews()
        status.text = ""
        content.addView(TextView(this).apply {
            text = "Mise à jour"
            textSize = 20f
            setPadding(0, 8, 0, 8)
        })
        updateMessage?.let { message ->
            content.addView(TextView(this).apply {
                text = message
                setPadding(0, 0, 0, 8)
            })
        }
        availableUpdate?.let { update ->
            content.addView(TextView(this).apply {
                val notes = update.releaseNotes.trim()
                text = "Version ${update.tag} disponible" + if (notes.isNotEmpty()) "\n$notes" else ""
                setPadding(0, 0, 0, 8)
            })
        }
        content.addView(Button(this).apply {
            text = when {
                updateRunning && availableUpdate != null -> "Téléchargement de ${availableUpdate?.tag}…"
                updateRunning -> "Vérification…"
                availableUpdate != null -> "Télécharger et installer"
                else -> "Rechercher une mise à jour"
            }
            isEnabled = !updateRunning
            setOnClickListener {
                if (availableUpdate == null) checkForAppUpdate() else installAppUpdate()
            }
        })
        content.addView(TextView(this).apply {
            text = "Version ${packageManager.getPackageInfo(packageName, 0).versionName}"
            setPadding(0, 4, 0, 12)
        })
        content.addView(TextView(this).apply {
            text = "Apparence"
            textSize = 20f
            setPadding(0, 8, 0, 8)
        })
        content.addView(TextView(this).apply { text = "Thème" })
        listOf("dark" to "Sombre", "light" to "Clair", "system" to "Système").chunked(2).forEach { themes ->
            val themeRow = actionRow()
            themes.forEach { (mode, label) ->
                themeRow.addView(Button(this).apply {
                    text = if (themeMode == mode) "• $label" else label
                    setOnClickListener {
                        if (themeMode != mode) {
                            themeMode = mode
                            database.setSetting("theme.mode", mode)
                            recreate()
                        }
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            content.addView(themeRow)
        }
        content.addView(TextView(this).apply {
            text = "Palette de couleurs"
            setPadding(0, 12, 0, 4)
        })
        listOf(
            listOf("violet" to "Violet", "ocean" to "Océan", "forest" to "Forêt", "teal" to "Lagon"),
            listOf("amber" to "Ambre", "sunset" to "Couchant", "rose" to "Rose", "slate" to "Ardoise"),
        ).flatten().chunked(4).forEach { row ->
            val paletteRow = actionRow()
            row.forEach { (key, label) ->
                paletteRow.addView(Button(this).apply {
                    tag = PaletteChoice(key)
                    text = if (paletteName == key) "\u2713" else ""
                    contentDescription = label
                    isSelected = paletteName == key
                    setOnClickListener {
                        paletteName = key
                        database.setSetting("theme.palette", key)
                        applyTheme(rootView)
                        renderSettings()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            content.addView(paletteRow)
        }
        content.addView(TextView(this).apply {
            text = "Podcasts"
            textSize = 20f
            setPadding(0, 8, 0, 8)
        })
        val interval = database.setting("feeds.interval", "360").toIntOrNull() ?: 360
        content.addView(TextView(this).apply {
            text = "Actualisation automatique : ${feedIntervalLabel(interval)}"
        })
        listOf(0 to "Jamais", 60 to "1 h", 360 to "6 h", 1440 to "24 h").chunked(2).forEach { intervals ->
            val intervalRow = actionRow()
            intervals.forEach { (minutes, label) ->
                intervalRow.addView(Button(this).apply {
                    text = label
                    setOnClickListener {
                        database.setSetting("feeds.interval", minutes.toString())
                        renderSettings()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            content.addView(intervalRow)
        }
        val mobileAllowed = database.setting("downloads.mobile", "0") == "1"
        content.addView(Button(this).apply {
            text = if (mobileAllowed) "Désactiver les téléchargements mobiles" else "Autoriser les données mobiles"
            setOnClickListener {
                database.setSetting("downloads.mobile", if (mobileAllowed) "0" else "1")
                downloadQueue.onNetworkPolicyChanged()
                renderSettings()
            }
        })
        val externalStorage = database.setting("storage.location", "internal") == "external"
        content.addView(Button(this).apply {
            text = "Stockage des prochains téléchargements : ${if (externalStorage) "Externe" else "Interne"}"
            setOnClickListener {
                database.setSetting("storage.location", if (externalStorage) "internal" else "external")
                renderSettings()
            }
        })
        val podcastCount = database.podcasts().size
        content.addView(Button(this).apply {
            text = "Actualiser les flux"
            isEnabled = podcastCount > 0
            setOnClickListener { refreshPodcasts(database.podcasts().map { it.feedUrl }) }
        })

        content.addView(TextView(this).apply {
            text = "Utilisation"
            textSize = 20f
            setPadding(0, 20, 0, 8)
        })
        val usage = downloadQueue.downloadedUsage()
        val totalBytes = usage.values.sumOf { it.second }
        val downloadedEpisodes = usage.values.sumOf { it.first }
        content.addView(TextView(this).apply {
            text = "$downloadedEpisodes épisodes · ${formatSize(totalBytes)} téléchargés\n${formatSize(filesDir.usableSpace)} libres (stockage interne)"
        })
        usage.forEach { (feedUrl, value) ->
            val title = database.podcast(feedUrl)?.title ?: feedUrl
            content.addView(TextView(this).apply {
                text = "$title · ${value.first} épisodes · ${formatSize(value.second)}"
                setPadding(0, 8, 0, 4)
            })
        }
        content.addView(Button(this).apply {
            text = "Supprimer tous les épisodes téléchargés"
            isEnabled = totalBytes > 0
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Supprimer les téléchargements ?")
                    .setMessage("Les abonnements aux podcasts seront conservés.")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Supprimer") { _, _ ->
                        downloadQueue.clearDownloads()
                        renderSettings()
                    }
                    .show()
            }
        })
    }

    private fun checkForAppUpdate() {
        if (updateRunning) return
        updateRunning = true
        updateMessage = null
        renderSettings()
        Thread {
            try {
                val update = AppUpdateManager(this).findUpdate()
                runOnUiThread {
                    availableUpdate = update
                    updateRunning = false
                    updateMessage = if (update == null) "L’application est à jour." else null
                    if (currentScreen == "settings") renderSettings()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateRunning = false
                    updateMessage = "Vérification impossible : ${error.localizedMessage ?: "erreur inconnue"}"
                    if (currentScreen == "settings") renderSettings()
                }
            }
        }.start()
    }

    private fun installAppUpdate() {
        val update = availableUpdate ?: return
        if (updateRunning) return
        val manager = AppUpdateManager(this)
        if (!manager.canInstallPackages()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
            status.text = "Autorisez l’installation depuis cette application, puis relancez-la."
            updateMessage = status.text.toString()
            return
        }
        updateRunning = true
        updateMessage = null
        renderSettings()
        Thread {
            try {
                val apk = manager.download(update)
                runOnUiThread {
                    updateRunning = false
                    try {
                        val installer = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                manager.installerUri(apk),
                                "application/vnd.android.package-archive",
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(installer)
                    } catch (error: Exception) {
                        updateMessage = "Impossible d’ouvrir l’installateur : ${error.localizedMessage ?: "erreur inconnue"}"
                    }
                    if (currentScreen == "settings") renderSettings()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateRunning = false
                    updateMessage = "Téléchargement impossible : ${error.localizedMessage ?: "erreur inconnue"}"
                    if (currentScreen == "settings") renderSettings()
                }
            }
        }.start()
    }

    private data class PaletteColors(
        val background: String,
        val surface: String,
        val surfaceAlt: String,
        val accent: String,
        val primaryText: String,
        val secondaryText: String,
    )

    private data class PaletteChoice(val key: String)

    private fun paletteColors(selectedPalette: String = paletteName): PaletteColors {
        val dark = when (themeMode) {
            "light" -> false
            "system" -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
        val palette = when (selectedPalette) {
            "ocean" -> Triple("#0a1220", "#0e1829", "#2f7de1")
            "forest" -> Triple("#08140f", "#0c1a13", "#1f9d6b")
            "teal" -> Triple("#081615", "#0b1d1c", "#12a39a")
            "amber" -> Triple("#17110a", "#1d150c", "#d98a0b")
            "sunset" -> Triple("#1a0e0b", "#20120e", "#e0694b")
            "rose" -> Triple("#190c13", "#1f0f18", "#e0527f")
            "slate" -> Triple("#0e1117", "#121720", "#64748b")
            else -> Triple("#100a1d", "#170f25", "#8052df")
        }
        return if (dark) {
            val surfaceAlt = when (selectedPalette) {
                "ocean" -> "#172338"
                "forest" -> "#15281f"
                "teal" -> "#123030"
                "amber" -> "#2e2312"
                "sunset" -> "#331d17"
                "rose" -> "#2e1625"
                "slate" -> "#1c2330"
                else -> "#281941"
            }
            PaletteColors(palette.first, palette.second, surfaceAlt, palette.third, "#f5f1fa", "#b8afc2")
        } else {
            val lightBackground = when (selectedPalette) {
                "ocean" -> "#f1f6fc"
                "forest" -> "#f1f9f4"
                "teal" -> "#eff9f8"
                "amber" -> "#fcf7ee"
                "sunset" -> "#fcf3ef"
                "rose" -> "#fcf1f5"
                "slate" -> "#f3f5f8"
                else -> "#f6f2fb"
            }
            val surfaceAlt = when (selectedPalette) {
                "ocean" -> "#e3edfa"
                "forest" -> "#e0f2e8"
                "teal" -> "#dff3f0"
                "amber" -> "#fbf0dc"
                "sunset" -> "#fae4dc"
                "rose" -> "#fbe6ee"
                "slate" -> "#e8ecf2"
                else -> "#ebe3f7"
            }
            PaletteColors(lightBackground, "#ffffff", surfaceAlt, palette.third, "#211b27", "#625b68")
        }
    }

    private fun installThemeListeners(group: ViewGroup) {
        for (index in 0 until group.childCount) {
            (group.getChildAt(index) as? ViewGroup)?.let(::installThemeListeners)
        }
        group.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                if (child != null) {
                    applyTheme(child)
                    (child as? ViewGroup)?.let(::installThemeListeners)
                }
            }

            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
    }

    private fun applyTheme(view: View) {
        val colors = paletteColors()
        when (view) {
            rootView -> view.setBackgroundColor(Color.parseColor(colors.background))
            content -> view.setBackgroundColor(Color.parseColor(colors.background))
            miniPlayer -> view.setBackgroundColor(Color.parseColor(colors.surfaceAlt))
            navigationBar -> view.setBackgroundColor(Color.parseColor(colors.surface))
            chapterDrawerHandle -> {
                view.setBackgroundColor(Color.parseColor(colors.surface))
            }
            settingsButton -> {
                settingsButton.setTextColor(Color.parseColor(colors.primaryText))
                settingsButton.background = roundedDrawable(colors.surfaceAlt, 22)
            }
            playPauseButton -> {
                playPauseButton.setTextColor(Color.WHITE)
                playPauseButton.background = roundedDrawable(colors.accent, 34)
            }
            playbackSpeedButton -> playbackSpeedButton.setTextColor(Color.parseColor(colors.accent))
            is Button -> {
                val primary = view.tag == "primary"
                view.setTextColor(Color.parseColor(if (primary) "#ffffff" else colors.primaryText))
                view.background = roundedDrawable(if (primary) colors.accent else colors.surfaceAlt, 12)
                view.isAllCaps = false
                view.setSingleLine(false)
                view.maxLines = Int.MAX_VALUE
                view.ellipsize = null
                view.minWidth = 0
                view.minimumWidth = 0
                view.minHeight = dp(48)
                view.minimumHeight = dp(48)
                val padding = if (view.tag == "icon") 0 else dp(12)
                view.setPadding(padding, padding, padding, padding)
                (view.tag as? PaletteChoice)?.let { choice ->
                    val accent = paletteColors(choice.key).accent
                    view.setTextColor(if (paletteColorsAreLight(accent)) Color.BLACK else Color.WHITE)
                    view.background = roundedDrawable(accent, 12).apply {
                        if (view.isSelected) setStroke(dp(3), Color.parseColor(colors.primaryText))
                    }
                }
            }
            is EditText -> {
                view.setTextColor(Color.parseColor(colors.primaryText))
                view.setHintTextColor(Color.parseColor(colors.secondaryText))
                view.background = roundedDrawable(colors.surfaceAlt, 12)
            }
            is TextView -> {
                val color = if (view.textSize >= 16f || view.typeface?.isBold == true) {
                    colors.primaryText
                } else {
                    colors.secondaryText
                }
                view.setTextColor(Color.parseColor(color))
            }
            is SeekBar -> {
                val accent = ColorStateList.valueOf(Color.parseColor(colors.accent))
                view.progressTintList = accent
                view.thumbTintList = accent
            }
            is android.widget.ProgressBar -> {
                view.progressTintList = ColorStateList.valueOf(Color.parseColor(colors.accent))
                view.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor(colors.surface))
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyTheme(view.getChildAt(index))
        }
        if (view == rootView) updateNavigationSelection()
        window.statusBarColor = Color.parseColor(colors.background)
        window.navigationBarColor = Color.parseColor(colors.background)
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = window.insetsController ?: return
            val lightBars = if (paletteColorsAreLight(colors.background)) {
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            } else 0
            insets.setSystemBarsAppearance(
                lightBars,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    }

    private fun roundedDrawable(color: String, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
        }

    private fun ovalDrawable(color: String): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }

    private fun outlineOvalDrawable(color: String, strokeDp: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(strokeDp), Color.parseColor(color))
        }

    private fun updateNavigationStatusBadge(badge: TextView) {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = connectivity.activeNetwork
        val online = connectivity.getNetworkCapabilities(active)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        badge.text = if (online) "●" else "○"
        badge.setTextColor(
            Color.parseColor(if (online) "#7be0a8" else "#cb715e"),
        )
    }

    private fun paletteColorsAreLight(color: String): Boolean {
        val parsed = Color.parseColor(color)
        return (0.299 * Color.red(parsed) + 0.587 * Color.green(parsed) + 0.114 * Color.blue(parsed)) > 160
    }

    private fun feedIntervalLabel(minutes: Int): String = when (minutes) {
        0 -> "Jamais"
        60 -> "1 h"
        360 -> "6 h"
        1440 -> "24 h"
        else -> "$minutes min"
    }

    private fun formatSize(bytes: Long): String {
        val megabytes = bytes / (1024.0 * 1024.0)
        return if (megabytes >= 1024) "%.2f Go".format(megabytes / 1024) else "%.1f Mo".format(megabytes)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun unsubscribePodcast(feedUrl: String) {
        Thread {
            try {
                downloadQueue.cancelFeedAndRun(feedUrl) { podcasts.unsubscribe(feedUrl) }
                runOnUiThread { renderPodcastsHome() }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Désabonnement impossible : ${error.localizedMessage ?: "erreur inconnue"}"
                }
            }
        }.start()
    }

    private fun playPodcast(
        podcast: SavedPodcast,
        requestedIndex: Int? = null,
        mobileAllowed: Boolean = false,
    ) {
        val player = controller ?: run {
            status.text = "Le lecteur audio démarre encore."
            return
        }
        val saved = database.progress(podcast.feedUrl)
        val startIndex = (requestedIndex ?: saved.trackIndex).coerceIn(0, podcast.episodes.lastIndex)
        val selectedEpisode = podcast.episodes[startIndex]
        val onMobile = isMobileNetwork()
        val mobileStreamingEnabled = database.setting("downloads.mobile", "0") == "1"
        if (selectedEpisode.localPath == null && onMobile && !mobileStreamingEnabled && !mobileAllowed) {
            AlertDialog.Builder(this)
                .setTitle("Lecture avec les données mobiles ?")
                .setMessage("Le réglage actuel limite l’utilisation du réseau mobile au Wi‑Fi. Lire cet épisode en streaming ?")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Lire") { _, _ ->
                    playPodcast(podcast, startIndex, mobileAllowed = true)
                }
                .show()
            return
        }
        val positionMs = if (startIndex == saved.trackIndex) saved.positionMs else 0L
        val episodes = if (onMobile && !mobileStreamingEnabled) {
            podcast.episodes.withIndex().filter {
                it.value.localPath != null || (mobileAllowed && it.index == startIndex)
            }
        } else {
            podcast.episodes.withIndex().toList()
        }
        val playableIndex = episodes.indexOfFirst { it.index == startIndex }
        if (playableIndex < 0) return
        val items = episodes.map { (index, episode) ->
            val uri = episode.localPath?.let { path ->
                if (path.startsWith("content://")) Uri.parse(path)
                else Uri.fromFile(java.io.File(path))
            } ?: Uri.parse(episode.remoteUrl)
            MediaItem.Builder()
                .setMediaId("${podcast.feedUrl}#$index")
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setArtist(podcast.title)
                        .apply {
                            (episode.artwork ?: podcast.artwork)?.let { setArtworkUri(Uri.parse(it)) }
                        }
                        .build(),
                )
                .build()
        }
        player.setMediaItems(items, playableIndex, positionMs)
        player.prepare()
        player.play()
        updatePlayerControls()
    }

    private fun isMobileNetwork(): Boolean {
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    private fun updatePlayerArtwork(book: AudioBook?, item: MediaItem?) {
        val local = book != null || item == null
        val address = if (local) book?.artworkPath else item?.mediaMetadata?.artworkUri?.toString()
        val source = PlayerArtworkSource(isLocal = local, address = address)
        if (playerArtworkSource == source) return
        playerArtworkSource = source
        if (local) {
            listOf(miniArtwork, artworkView).forEach { view ->
                view.tag = null
                view.setImageDrawable(null)
                if (!address.isNullOrBlank()) {
                    view.setImageURI(Uri.fromFile(java.io.File(address)))
                }
                if (view.drawable == null) {
                    view.setImageResource(android.R.drawable.ic_media_play)
                }
            }
        } else {
            val allowNetwork = !isMobileNetwork() || database.setting("downloads.mobile", "0") == "1"
            podcastArtwork.load(miniArtwork, address, allowNetwork)
            podcastArtwork.load(artworkView, address, allowNetwork)
        }
    }

    private fun updatePlayerControls() {
        updateChapterDrawer()
        val player = controller ?: return
        val metadataTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()
        val metadataArtist = player.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty()
        val mediaId = player.currentMediaItem?.mediaId
        val separator = mediaId?.lastIndexOf('#') ?: -1
        val book = if (separator > 0) libraryBooks.firstOrNull { it.id == mediaId?.substring(0, separator) } else null
        val trackIndex = if (separator > 0) mediaId?.substring(separator + 1)?.toIntOrNull() else null
        val track = if (book != null && trackIndex != null) book.tracks.getOrNull(trackIndex) else null
        updatePlayerArtwork(book, player.currentMediaItem)
        nowPlaying.text = track?.name ?: metadataTitle ?: "Rien en lecture"
        nowPlayingArtist.text = book?.title ?: metadataArtist
        playPauseButton.text = if (player.isPlaying) "\ue39e" else "\ue3d0"
        playbackSpeedButton.text = "${playbackSpeed()}×"
        miniTitle.text = track?.name ?: metadataTitle ?: "Rien en lecture"
        miniSubtitle.text = book?.title ?: metadataArtist
        miniPlayButton.text = if (player.isPlaying) "Ⅱ" else "▶"
        miniPlayer.visibility = if (player.currentMediaItem != null && currentScreen != "player") View.VISIBLE else View.GONE
        durationMs = player.duration.takeIf { it > 0 } ?: 0L
        val chapterProgress = if (durationMs > 0) {
            (player.currentPosition.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        if (vinylSurface.width > 0 && artworkView.width > 0) {
            val centerX = vinylSurface.left + vinylSurface.width / 2f
            val centerY = vinylSurface.top + vinylSurface.height / 2f
            val pivotX = tonearm.left + tonearm.pivotX
            val pivotY = tonearm.top + tonearm.pivotY
            val dx = (pivotX - centerX).toDouble()
            val dy = (pivotY - centerY).toDouble()
            val pivotDistance = kotlin.math.hypot(dx, dy)
            val needleDistance = dp(85).toDouble()
            val outerRadius = vinylSurface.width / 2.0 - dp(3)
            val innerRadius = artworkView.width / 2.0 + dp(3)
            val radius = outerRadius + (innerRadius - outerRadius) * chapterProgress
            val cosine = ((pivotDistance * pivotDistance + needleDistance * needleDistance - radius * radius) /
                (2.0 * pivotDistance * needleDistance)).coerceIn(-1.0, 1.0)
            tonearm.rotation = Math.toDegrees(
                kotlin.math.atan2(dx, -dy) - kotlin.math.acos(cosine),
            ).toFloat()
        }
        vinylAnimator?.let { animator ->
            if (player.isPlaying) {
                if (!animator.isStarted) animator.start()
                else if (animator.isPaused) animator.resume()
            } else if (animator.isStarted && !animator.isPaused) {
                animator.pause()
            }
        }
        seekBar.max = (durationMs / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (!seeking) {
            seekBar.progress = (player.currentPosition / 1_000L).coerceIn(0, seekBar.max.toLong()).toInt()
        }
        currentTime.text = formatTime(player.currentPosition)
        totalTime.text = formatTime(durationMs)
        miniProgress.progress = if (durationMs > 0) {
            ((player.currentPosition * 1000L) / durationMs).toInt().coerceIn(0, 1000)
        } else {
            0
        }

        updateNavigationSelection()
    }

    private fun formatTime(positionMs: Long): String {
        val totalSeconds = (positionMs.coerceAtLeast(0) / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun updateNavigationSelection() {
        if (!::content.isInitialized) return
        val selected = when (currentScreen) {
            "podcasts", "podcast", "downloads" -> "podcasts"
            "player" -> "player"
            else -> "library"
        }
        val colors = paletteColors()
        navigationButtons.forEach { (tab, button) ->
            val active = tab == selected
            button.setTextColor(Color.parseColor(if (active) colors.accent else colors.secondaryText))
        }
    }

    private fun playbackSpeed(): Float =
        database.setting("playback.speed", "1.0")
            .toFloatOrNull()
            ?.takeIf { it.isFinite() && it in 0.5f..2f }
            ?: 1f

    private fun cyclePlaybackSpeed() {
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val current = playbackSpeed()
        val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
        database.setSetting("playback.speed", next.toString())
        controller?.setPlaybackSpeed(next)
        playbackSpeedButton.text = "${next}×"
    }

    private fun skipTrack(delta: Int) {
        val player = controller ?: return
        val target = player.currentMediaItemIndex + delta
        if (target in 0 until player.mediaItemCount) {
            player.seekTo(target, 0)
            player.play()
            updatePlayerControls()
        }
    }

    override fun onStart() {
        super.onStart()
        refreshHandler.post(refreshTask)
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshTask)
        super.onStop()
    }

    override fun onDestroy() {
        podcastArtwork.close()
        closeChapterDrawer()
        feedRefreshHandler.removeCallbacksAndMessages(null)
        vinylAnimator?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        downloadQueue.setOnChanged {}
        database.close()
        super.onDestroy()
    }
}
