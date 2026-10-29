package org.nativescript.audiobookplayer;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class Downloader {
    public interface Callback {
        void onProgress(double done, double total);

        void onDone();

        void onError(String message);

        // Called when the transfer was interrupted by pause() or cancel().
        void onStopped(boolean cancelled);
    }

    public static final class Job {
        private volatile boolean paused;
        private volatile boolean cancelled;

        public void pause() {
            paused = true;
        }

        public void cancel() {
            cancelled = true;
        }
    }

    private Downloader() {}

    /**
     * Downloads url into target (a file path or a content:// URI). Existing partial data is kept
     * and continued with an HTTP Range request, so a stopped job can be restarted by calling start again.
     */
    public static Job start(final Context context, final String url, final String target, final Callback callback) {
        final Job job = new Job();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean isUri = target.startsWith("content://");
                File part = isUri ? null : new File(target + ".part");
                try {
                    long existing = isUri ? uriSize(context, target) : (part.exists() ? part.length() : 0);
                    HttpURLConnection conn = connect(url, existing);
                    boolean resumed = existing > 0 && conn.getResponseCode() == 206;
                    long offset = resumed ? existing : 0;
                    long length = conn.getContentLengthLong();
                    long total = length < 0 ? -1 : offset + length;

                    InputStream in = conn.getInputStream();
                    OutputStream out = isUri
                        ? context.getContentResolver().openOutputStream(Uri.parse(target), resumed ? "wa" : "wt")
                        : new FileOutputStream(part, resumed);
                    byte[] buffer = new byte[65536];
                    long done = offset;
                    int n;
                    boolean stopped = false;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        done += n;
                        callback.onProgress(done, total);
                        if (job.paused || job.cancelled) {
                            stopped = true;
                            break;
                        }
                    }
                    out.close();
                    in.close();
                    conn.disconnect();

                    if (stopped) {
                        if (job.cancelled) {
                            discard(context, target);
                        }
                        callback.onStopped(job.cancelled);
                        return;
                    }
                    if (!isUri) {
                        File dest = new File(target);
                        if (dest.exists()) {
                            dest.delete();
                        }
                        if (!part.renameTo(dest)) {
                            throw new Exception("Renommage impossible");
                        }
                    }
                    callback.onDone();
                } catch (Exception e) {
                    if (job.cancelled) {
                        discard(context, target);
                        callback.onStopped(true);
                    } else {
                        callback.onError(String.valueOf(e.getMessage()));
                    }
                }
            }
        }).start();
        return job;
    }

    // Removes the partial data (also used when cancelling a download that is not running).
    public static void discard(Context context, String target) {
        try {
            if (target.startsWith("content://")) {
                android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), Uri.parse(target));
            } else {
                new File(target + ".part").delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static long uriSize(Context context, String uri) {
        try {
            ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(Uri.parse(uri), "r");
            long size = fd.getStatSize();
            fd.close();
            return Math.max(0, size);
        } catch (Exception e) {
            return 0;
        }
    }

    private static HttpURLConnection connect(String url, long rangeStart) throws Exception {
        URL current = new URL(url);
        for (int i = 0; i < 8; i++) {
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AudiobookPlayer");
            if (rangeStart > 0) {
                conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = conn.getHeaderField("Location");
                conn.disconnect();
                if (next == null) {
                    throw new Exception("Redirection invalide");
                }
                current = new URL(current, next);
                continue;
            }
            if (code != 200 && code != 206) {
                throw new Exception("HTTP " + code);
            }
            return conn;
        }
        throw new Exception("Trop de redirections");
    }
}
