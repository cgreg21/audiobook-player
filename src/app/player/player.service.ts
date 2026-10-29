import { inject, Injectable, signal } from '@angular/core';
import { Application, Utils } from '@nativescript/core';
import { DatabaseService, Progress } from '../data/database.service';
import { Book } from '../library/library.service';

declare const org: any;
declare const androidx: any;

const POLL_MS = 1000;
const SAVE_EVERY_TICKS = 2;
const FINISHED_RATIO = 0.95;

@Injectable({ providedIn: 'root' })
export class PlayerService {
  private readonly db = inject(DatabaseService);

  constructor() {
    // The app can be killed right after backgrounding: persist the position beforehand.
    Application.on(Application.suspendEvent, () => this.saveProgress());
    Application.on(Application.exitEvent, () => this.saveProgress());
  }
  readonly book = signal<Book | null>(null);
  readonly trackIndex = signal(0);
  readonly playing = signal(false);
  readonly positionMs = signal(0);
  readonly durationMs = signal(0);

  private timer: ReturnType<typeof setInterval> | null = null;
  private ticks = 0;
  private readonly readTick = signal(0);
  private readonly readCache = new Map<string, Set<number>>();
  private lastObserved: { bookId: string; index: number; ratio: number } | null = null;
  // The controller applies load() asynchronously: until it reports the saved spot, its state is stale and must not be saved.
  private pendingResume: { index: number; positionMs: number; since: number; retried: boolean } | null = null;

  private get bridge(): any {
    return org.nativescript.audiobookplayer.PlayerBridge;
  }

  async playBook(book: Book, trackIndex?: number): Promise<void> {
    await this.connect();
    this.requestNotificationPermission();

    const saved = this.readProgress(book.id);
    if (this.book()?.id === book.id && trackIndex === undefined) {
      this.bridge.play();
      this.refresh();
      return;
    }

    const index = trackIndex ?? Math.min(saved.index, book.tracks.length - 1);
    const positionMs = trackIndex === undefined || trackIndex === saved.index ? saved.positionMs : 0;

    const uris = Array.create('java.lang.String', book.tracks.length);
    const titles = Array.create('java.lang.String', book.tracks.length);
    book.tracks.forEach((track, i) => {
      uris[i] = this.toPlayableUri(track.uri || track.remoteUrl || '');
      titles[i] = track.name;
    });

    this.saveProgress();
    this.book.set(book);
    this.trackIndex.set(index);
    this.positionMs.set(positionMs);
    this.pendingResume = positionMs > 0 || index > 0 ? { index, positionMs, since: Date.now(), retried: false } : null;
    this.bridge.load(uris, titles, book.title, book.artwork ? this.toPlayableUri(book.artwork) : '', index, positionMs, true);
    this.startPolling();
  }

  toggle(): void {
    if (!this.book()) {
      return;
    }
    this.playing() ? this.bridge.pause() : this.bridge.play();
    this.refresh();
    this.saveProgress();
  }

  seekTo(ms: number): void {
    if (this.book()) {
      this.bridge.seekTo(ms);
      this.positionMs.set(ms);
    }
  }

  seekBy(deltaMs: number): void {
    if (this.book()) {
      this.bridge.seekBy(deltaMs);
      this.refresh();
    }
  }

  skipTo(index: number): void {
    const book = this.book();
    if (book && index >= 0 && index < book.tracks.length) {
      this.bridge.seekToTrack(index);
      this.refresh();
    }
  }

  resumeProgress(book: Book): Progress {
    return this.readProgress(book.id);
  }

  private connect(): Promise<void> {
    return new Promise((resolve) => {
      this.bridge.connect(
        Utils.android.getApplicationContext(),
        new java.lang.Runnable({ run: () => resolve() }),
      );
    });
  }

  private requestNotificationPermission(): void {
    const activity = Application.android.foregroundActivity;
    if (android.os.Build.VERSION.SDK_INT >= 33 && activity) {
      const permission = 'android.permission.POST_NOTIFICATIONS';
      const granted = androidx.core.content.ContextCompat.checkSelfPermission(activity, permission) === 0;
      if (!granted) {
        const permissions = Array.create('java.lang.String', 1);
        permissions[0] = permission;
        androidx.core.app.ActivityCompat.requestPermissions(activity, permissions, 4202);
      }
    }
  }

  private toPlayableUri(uri: string): string {
    return uri.startsWith('/') ? `file://${uri}` : uri;
  }

  private startPolling(): void {
    if (this.timer) {
      return;
    }
    this.timer = setInterval(() => {
      this.refresh();
      if (++this.ticks % SAVE_EVERY_TICKS === 0) {
        this.saveProgress();
      }
    }, POLL_MS);
  }

  private refresh(): void {
    if (!this.book() || !this.bridge.isConnected()) {
      return;
    }
    const wasPlaying = this.playing();
    this.playing.set(this.bridge.isPlaying());
    if (this.pendingResume && !this.checkResumed(this.pendingResume)) {
      return;
    }
    if (wasPlaying && !this.playing()) {
      this.saveProgress();
    }
    this.trackIndex.set(this.bridge.trackIndex());
    this.positionMs.set(Number(this.bridge.position()));
    this.durationMs.set(Number(this.bridge.duration()));
    this.trackReadChapters();
  }

  private checkResumed(target: { index: number; positionMs: number; since: number; retried: boolean }): boolean {
    const atTarget =
      this.bridge.trackIndex() === target.index &&
      Number(this.bridge.duration()) > 0 &&
      Math.abs(Number(this.bridge.position()) - target.positionMs) < 5000;
    if (atTarget) {
      this.pendingResume = null;
      return true;
    }
    const waited = Date.now() - target.since;
    if (waited > 3000 && !target.retried && Number(this.bridge.duration()) > 0) {
      target.retried = true;
      this.bridge.seekToTrack(target.index);
      this.bridge.seekTo(target.positionMs);
    } else if (waited > 8000) {
      this.pendingResume = null;
    }
    return false;
  }

  private readProgress(bookId: string): Progress {
    return this.db.getProgress(bookId);
  }

  private saveProgress(): void {
    const book = this.book();
    if (book && !this.pendingResume && this.bridge.isConnected() && Number(this.bridge.duration()) > 0) {
      const progress: Progress = {
        index: this.bridge.trackIndex(),
        positionMs: Number(this.bridge.position()),
        durationMs: Number(this.bridge.duration()),
      };
      this.db.saveProgress(book.id, progress);
    }
  }

  lastPlayedBookId(): string | null {
    return this.db.lastPlayedBookId();
  }

  // Only chapters listened to the end count: skipped or merely started chapters are ignored.
  bookFraction(book: Book): number {
    this.readTick();
    return Math.min(1, this.readSet(book.id).size / Math.max(1, book.tracks.length));
  }

  isChapterRead(bookId: string, index: number): boolean {
    this.readTick();
    return this.readSet(bookId).has(index);
  }

  private readSet(bookId: string): Set<number> {
    let set = this.readCache.get(bookId);
    if (!set) {
      set = new Set(this.db.readChapters(bookId));
      this.readCache.set(bookId, set);
    }
    return set;
  }

  private trackReadChapters(): void {
    const book = this.book();
    const index = this.trackIndex();
    const duration = this.durationMs();
    const ratio = duration > 0 ? this.positionMs() / duration : 0;
    if (!book) {
      return;
    }
    if (this.lastObserved && this.lastObserved.bookId === book.id && this.lastObserved.index !== index) {
      if (this.lastObserved.ratio >= FINISHED_RATIO) {
        this.markRead(book.id, this.lastObserved.index);
      }
    }
    if (this.playing() && ratio >= FINISHED_RATIO) {
      this.markRead(book.id, index);
    }
    this.lastObserved = { bookId: book.id, index, ratio };
  }

  // Lu : toutes les pistes marquées ; non lu : lecture effacée, la position de reprise est conservée.
  setBookRead(book: Book, read: boolean): void {
    if (read) {
      book.tracks.forEach((_, index) => this.markRead(book.id, index));
      return;
    }
    this.db.clearChaptersRead(book.id);
    this.readCache.delete(book.id);
    this.readTick.update((n) => n + 1);
  }

  private markRead(bookId: string, index: number): void {
    const set = this.readSet(bookId);
    if (!set.has(index)) {
      set.add(index);
      this.db.markChapterRead(bookId, index);
      this.readTick.update((n) => n + 1);
    }
  }
}
