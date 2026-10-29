package org.nativescript.audiobookplayer;

import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/** Foreground media service: owns the ExoPlayer and exposes it through a MediaSession (notification, lock screen, headset). */
public class PlaybackService extends MediaSessionService {
    private MediaSession session;
    private ExoPlayer player;

    @Override
    public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    player.seekTo(0);
                }
            }
        });
        session = new MediaSession.Builder(this, player).build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        player.pause();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        session.release();
        player.release();
        super.onDestroy();
    }
}
