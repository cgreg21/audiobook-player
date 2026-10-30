package org.nativescript.audiobookplayer

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var database: LibraryDatabase
    private val handler = Handler(Looper.getMainLooper())

    private val saveProgressTask = object : Runnable {
        override fun run() {
            saveCurrentProgress()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = LibraryDatabase(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val speed = database.setting("playback.speed", "1.0")
            .toFloatOrNull()
            ?.takeIf { it.isFinite() && it in 0.5f..2f }
            ?: 1f
        player.setPlaybackSpeed(speed)
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    oldPosition.mediaItem?.mediaId?.let(::markChapterCompleted)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.currentMediaItem?.mediaId?.let(::markChapterCompleted)
                    saveCurrentProgress()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveCurrentProgress()
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                saveCurrentProgress()
            }
        })
        session = MediaSession.Builder(this, player).build()
        handler.post(saveProgressTask)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrentProgress()
        player.pause()
        player.stop()
        stopSelf()
    }

    private fun saveCurrentProgress() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val separator = mediaId.lastIndexOf('#')
        if (separator <= 0) return
        val bookId = mediaId.substring(0, separator)
        val trackIndex = mediaId.substring(separator + 1).toIntOrNull() ?: return
        database.saveProgress(bookId, trackIndex, player.currentPosition.coerceAtLeast(0))
        val duration = player.duration
        if (duration > 0 && player.currentPosition.toDouble() / duration >= 0.95) {
            markChapterCompleted(mediaId)
        }
    }

    private fun markChapterCompleted(mediaId: String) {
        val separator = mediaId.lastIndexOf('#')
        if (separator <= 0) return
        val trackIndex = mediaId.substring(separator + 1).toIntOrNull() ?: return
        database.markChapterRead(mediaId.substring(0, separator), trackIndex)
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveProgressTask)
        saveCurrentProgress()
        session.release()
        player.release()
        database.close()
        super.onDestroy()
    }
}
