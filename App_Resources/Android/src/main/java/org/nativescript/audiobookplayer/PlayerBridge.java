package org.nativescript.audiobookplayer;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;

import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/** Thin static facade over a MediaController so the JS side only deals with primitives. */
public final class PlayerBridge {
    private static MediaController controller;

    private PlayerBridge() {}

    public static void connect(final Context context, final Runnable onReady) {
        if (controller != null) {
            onReady.run();
            return;
        }
        SessionToken token = new SessionToken(context, new ComponentName(context, PlaybackService.class));
        final ListenableFuture<MediaController> future = new MediaController.Builder(context, token).buildAsync();
        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    controller = future.get();
                    onReady.run();
                } catch (Exception ignored) {
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    public static void load(String[] uris, String[] titles, String artist, String artwork,
                            int index, long positionMs, boolean play) {
        List<MediaItem> items = new ArrayList<MediaItem>();
        for (int i = 0; i < uris.length; i++) {
            MediaMetadata.Builder meta = new MediaMetadata.Builder().setTitle(titles[i]).setArtist(artist);
            if (artwork != null && artwork.length() > 0) {
                meta.setArtworkUri(Uri.parse(artwork));
            }
            items.add(new MediaItem.Builder()
                    .setUri(uris[i])
                    .setMediaId(String.valueOf(i))
                    .setMediaMetadata(meta.build())
                    .build());
        }
        controller.setMediaItems(items, index, positionMs);
        controller.prepare();
        if (play) {
            controller.play();
        }
    }

    public static boolean isConnected() { return controller != null; }
    public static void play() { controller.play(); }
    public static void pause() { controller.pause(); }
    public static void seekTo(long positionMs) { controller.seekTo(positionMs); }
    public static void seekBy(long deltaMs) {
        long target = Math.max(0, controller.getCurrentPosition() + deltaMs);
        long duration = controller.getDuration();
        controller.seekTo(duration > 0 ? Math.min(target, duration) : target);
    }
    public static void seekToTrack(int index) { controller.seekTo(index, 0); }
    public static boolean isPlaying() { return controller != null && controller.isPlaying(); }
    public static int trackIndex() { return controller.getCurrentMediaItemIndex(); }
    public static long position() { return controller.getCurrentPosition(); }
    public static long duration() { return Math.max(0, controller.getDuration()); }
}
