package org.nativescript.audiobookplayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class Downloader {
    public interface Callback {
        void onDone();

        void onError(String message);
    }

    private Downloader() {}

    public static void downloadToUri(final android.content.Context context, final String url, final String targetUri, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection conn = connect(url);
                    InputStream in = conn.getInputStream();
                    OutputStream out = context.getContentResolver().openOutputStream(android.net.Uri.parse(targetUri));
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                    out.close();
                    in.close();
                    conn.disconnect();
                    callback.onDone();
                } catch (Exception e) {
                    try {
                        android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), android.net.Uri.parse(targetUri));
                    } catch (Exception ignored) {
                    }
                    callback.onError(String.valueOf(e.getMessage()));
                }
            }
        }).start();
    }

    private static HttpURLConnection connect(String url) throws Exception {
        HttpURLConnection conn = null;
        URL current = new URL(url);
        for (int i = 0; i < 8; i++) {
            conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AudiobookPlayer");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = conn.getHeaderField("Location");
                conn.disconnect();
                if (next == null) {
                    throw new Exception("Redirection invalide");
                }
                current = new URL(current, next);
                conn = null;
                continue;
            }
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            return conn;
        }
        throw new Exception("Trop de redirections");
    }

    public static void download(final String url, final String target, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(target + ".part");
                try {
                    HttpURLConnection conn = null;
                    URL current = new URL(url);
                    for (int i = 0; i < 8; i++) {
                        conn = (HttpURLConnection) current.openConnection();
                        conn.setInstanceFollowRedirects(false);
                        conn.setConnectTimeout(20000);
                        conn.setReadTimeout(30000);
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AudiobookPlayer");
                        int code = conn.getResponseCode();
                        if (code >= 300 && code < 400) {
                            String next = conn.getHeaderField("Location");
                            conn.disconnect();
                            if (next == null) {
                                throw new Exception("Redirection invalide");
                            }
                            current = new URL(current, next);
                            conn = null;
                            continue;
                        }
                        if (code != 200) {
                            throw new Exception("HTTP " + code);
                        }
                        break;
                    }
                    if (conn == null) {
                        throw new Exception("Trop de redirections");
                    }
                    InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(tmp);
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                    out.close();
                    in.close();
                    conn.disconnect();
                    File dest = new File(target);
                    if (dest.exists()) {
                        dest.delete();
                    }
                    if (!tmp.renameTo(dest)) {
                        throw new Exception("Renommage impossible");
                    }
                    callback.onDone();
                } catch (Exception e) {
                    tmp.delete();
                    callback.onError(String.valueOf(e.getMessage()));
                }
            }
        }).start();
    }
}
