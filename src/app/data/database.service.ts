import { Injectable } from '@angular/core';
import { ApplicationSettings, Utils } from '@nativescript/core';
import type { Episode, Podcast } from '../library/podcast.service';

declare const java: any;

const LEGACY_PODCASTS = 'podcasts.v1';
const LEGACY_TREES = 'library.treeUris';
const LEGACY_PROGRESS_PREFIX = 'progress.';

export interface Progress {
  index: number;
  positionMs: number;
  durationMs?: number;
}

@Injectable({ providedIn: 'root' })
export class DatabaseService {
  private readonly db: any;

  constructor() {
    const context = Utils.android.getApplicationContext();
    this.db = context.openOrCreateDatabase('audiobooks.db', 0, null);
    this.db.execSQL('PRAGMA foreign_keys = ON');
    this.createSchema();
    this.migrateLegacy();
  }

  loadTreeUris(): string[] {
    return this.query('SELECT uri FROM folders ORDER BY rowid', [], (c) => c.getString(0));
  }

  addTreeUri(uri: string): void {
    this.exec('INSERT OR IGNORE INTO folders (uri) VALUES (?)', [uri]);
  }

  loadPodcasts(): Podcast[] {
    const podcasts = this.query(
      'SELECT feed_url, title, author, artwork FROM podcasts ORDER BY position',
      [],
      (c) => ({
        feedUrl: c.getString(0),
        title: c.getString(1),
        author: c.getString(2),
        artwork: c.isNull(3) ? undefined : c.getString(3),
        episodes: [] as Episode[],
      }),
    );
    for (const podcast of podcasts) {
      podcast.episodes = this.query(
        'SELECT id, title, remote_url, local_path FROM episodes WHERE feed_url = ? ORDER BY position',
        [podcast.feedUrl],
        (c) => ({
          id: c.getString(0),
          title: c.getString(1),
          remoteUrl: c.getString(2),
          localPath: c.isNull(3) ? undefined : c.getString(3),
        }),
      );
    }
    return podcasts;
  }

  savePodcast(podcast: Podcast, position: number): void {
    this.transaction(() => {
      this.exec(
        'INSERT OR REPLACE INTO podcasts (feed_url, title, author, artwork, position) VALUES (?, ?, ?, ?, ?)',
        [podcast.feedUrl, podcast.title, podcast.author, podcast.artwork ?? null, position],
      );
      this.exec('DELETE FROM episodes WHERE feed_url = ?', [podcast.feedUrl]);
      podcast.episodes.forEach((e, i) =>
        this.exec(
          'INSERT INTO episodes (id, feed_url, title, remote_url, local_path, position) VALUES (?, ?, ?, ?, ?, ?)',
          [e.id, podcast.feedUrl, e.title, e.remoteUrl, e.localPath ?? null, i],
        ),
      );
    });
  }

  deletePodcast(feedUrl: string): void {
    this.exec('DELETE FROM podcasts WHERE feed_url = ?', [feedUrl]);
  }

  getProgress(bookId: string): Progress {
    const rows = this.query(
      'SELECT track_index, position_ms, duration_ms FROM progress WHERE book_id = ?',
      [bookId],
      (c) => ({ index: c.getInt(0), positionMs: Number(c.getLong(1)), durationMs: Number(c.getLong(2)) }),
    );
    return rows[0] ?? { index: 0, positionMs: 0, durationMs: 0 };
  }

  lastPlayedBookId(): string | null {
    const rows = this.query(
      'SELECT book_id FROM progress ORDER BY updated_at DESC LIMIT 1',
      [],
      (c) => c.getString(0),
    );
    return rows[0] ?? null;
  }

  getSetting(key: string, fallback: string): string {
    const rows = this.query('SELECT value FROM settings WHERE key = ?', [key], (c) => c.getString(0));
    return rows[0] ?? fallback;
  }

  setSetting(key: string, value: string): void {
    this.exec('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)', [key, value]);
  }

  removeTreeUri(uri: string): void {
    this.exec('DELETE FROM folders WHERE uri = ?', [uri]);
  }

  fileSize(): number {
    return Number(new java.io.File(this.db.getPath()).length());
  }

  markChapterRead(bookId: string, trackIndex: number): void {
    this.exec('INSERT OR IGNORE INTO chapters_read (book_id, track_index) VALUES (?, ?)', [bookId, trackIndex]);
  }

  clearChaptersRead(bookId: string): void {
    this.exec('DELETE FROM chapters_read WHERE book_id = ?', [bookId]);
  }

  readChapters(bookId: string): number[] {
    return this.query('SELECT track_index FROM chapters_read WHERE book_id = ?', [bookId], (c) => c.getInt(0));
  }

  saveProgress(bookId: string, progress: Progress): void {
    this.exec(
      'INSERT OR REPLACE INTO progress (book_id, track_index, position_ms, duration_ms, updated_at) VALUES (?, ?, ?, ?, ?)',
      [bookId, progress.index, progress.positionMs, progress.durationMs ?? 0, Date.now()],
    );
  }

  private createSchema(): void {
    this.db.execSQL('CREATE TABLE IF NOT EXISTS folders (uri TEXT PRIMARY KEY)');
    this.db.execSQL(
      'CREATE TABLE IF NOT EXISTS podcasts (feed_url TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL, artwork TEXT, position INTEGER NOT NULL)',
    );
    this.db.execSQL(
      'CREATE TABLE IF NOT EXISTS episodes (id TEXT NOT NULL, feed_url TEXT NOT NULL REFERENCES podcasts(feed_url) ON DELETE CASCADE, title TEXT NOT NULL, remote_url TEXT NOT NULL, local_path TEXT, position INTEGER NOT NULL, PRIMARY KEY (feed_url, id))',
    );
    this.db.execSQL(
      'CREATE TABLE IF NOT EXISTS progress (book_id TEXT PRIMARY KEY, track_index INTEGER NOT NULL, position_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)',
    );
    this.db.execSQL('CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)');
    this.db.execSQL(
      'CREATE TABLE IF NOT EXISTS chapters_read (book_id TEXT NOT NULL, track_index INTEGER NOT NULL, PRIMARY KEY (book_id, track_index))',
    );
    for (const column of ['duration_ms', 'updated_at']) {
      try {
        this.db.execSQL(`ALTER TABLE progress ADD COLUMN ${column} INTEGER NOT NULL DEFAULT 0`);
      } catch {
        // Column already exists.
      }
    }
  }

  // One-time import of data previously stored in ApplicationSettings.
  private migrateLegacy(): void {
    if (ApplicationSettings.hasKey(LEGACY_TREES)) {
      const uris: string[] = JSON.parse(ApplicationSettings.getString(LEGACY_TREES, '[]'));
      uris.forEach((uri) => this.addTreeUri(uri));
      ApplicationSettings.remove(LEGACY_TREES);
    }
    if (ApplicationSettings.hasKey(LEGACY_PODCASTS)) {
      const podcasts: Podcast[] = JSON.parse(ApplicationSettings.getString(LEGACY_PODCASTS, '[]'));
      podcasts.forEach((p, i) => this.savePodcast(p, i));
      ApplicationSettings.remove(LEGACY_PODCASTS);
    }
    for (const key of ApplicationSettings.getAllKeys()) {
      if (key.startsWith(LEGACY_PROGRESS_PREFIX)) {
        const progress: Progress = JSON.parse(ApplicationSettings.getString(key, '{"index":0,"positionMs":0}'));
        this.saveProgress(key.substring(LEGACY_PROGRESS_PREFIX.length), progress);
        ApplicationSettings.remove(key);
      }
    }
  }

  private exec(sql: string, args: unknown[]): void {
    const statement = this.db.compileStatement(sql);
    try {
      args.forEach((value, i) => {
        const index = i + 1;
        if (value === null || value === undefined) {
          statement.bindNull(index);
        } else if (typeof value === 'number') {
          statement.bindLong(index, value);
        } else {
          statement.bindString(index, String(value));
        }
      });
      statement.execute();
    } finally {
      statement.close();
    }
  }

  private transaction(work: () => void): void {
    this.db.beginTransaction();
    try {
      work();
      this.db.setTransactionSuccessful();
    } finally {
      this.db.endTransaction();
    }
  }

  private query<T>(sql: string, args: string[], map: (cursor: any) => T): T[] {
    const cursor = this.db.rawQuery(sql, args.length ? args : null);
    const rows: T[] = [];
    try {
      while (cursor.moveToNext()) {
        rows.push(map(cursor));
      }
    } finally {
      cursor.close();
    }
    return rows;
  }

}
