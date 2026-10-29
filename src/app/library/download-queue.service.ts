import { inject, Injectable, signal } from '@angular/core';
import { Utils } from '@nativescript/core';
import { PodcastService } from './podcast.service';

declare const org: any;

export type DownloadStatus = 'queued' | 'downloading' | 'paused' | 'error';

export interface DownloadItem {
  key: string;
  feedUrl: string;
  episodeId: string;
  title: string;
  status: DownloadStatus;
  done: number;
  total: number;
  error?: string;
}

interface Runtime {
  url: string;
  target: string;
  job?: any;
}

const MAX_PARALLEL = 1;
const PROGRESS_THROTTLE_MS = 300;

// File d'attente de téléchargements : un seul transfert actif, les autres attendent.
@Injectable({ providedIn: 'root' })
export class DownloadQueueService {
  private readonly podcasts = inject(PodcastService);
  private readonly runtime = new Map<string, Runtime>();
  private lastEmit = 0;

  readonly items = signal<DownloadItem[]>([]);
  // Appelé quand un téléchargement se termine ou est annulé, pour rafraîchir le catalogue.
  onChanged: () => void = () => undefined;

  static key(feedUrl: string, episodeId: string): string {
    return `${feedUrl}|${episodeId}`;
  }

  percent(item: DownloadItem): number {
    return Math.round(this.fraction(item) * 100);
  }

  find(feedUrl: string, episodeId: string): DownloadItem | undefined {
    const key = DownloadQueueService.key(feedUrl, episodeId);
    return this.items().find((i) => i.key === key);
  }

  fraction(item: DownloadItem): number {
    return item.total > 0 ? Math.min(1, item.done / item.total) : 0;
  }

  enqueue(feedUrl: string, episodeId: string): void {
    if (this.find(feedUrl, episodeId)) {
      return;
    }
    const prepared = this.podcasts.prepareDownload(feedUrl, episodeId);
    if (!prepared) {
      return;
    }
    const key = DownloadQueueService.key(feedUrl, episodeId);
    this.runtime.set(key, prepared);
    this.items.update((list) => [
      ...list,
      { key, feedUrl, episodeId, title: this.podcasts.episodeTitle(feedUrl, episodeId), status: 'queued', done: 0, total: 0 },
    ]);
    this.next();
  }

  pause(key: string): void {
    const item = this.items().find((i) => i.key === key);
    if (!item) {
      return;
    }
    if (item.status === 'downloading') {
      this.runtime.get(key)?.job?.pause();
    } else if (item.status === 'queued') {
      this.patch(key, { status: 'paused' });
    }
  }

  resume(key: string): void {
    const item = this.items().find((i) => i.key === key);
    if (item && (item.status === 'paused' || item.status === 'error')) {
      this.patch(key, { status: 'queued', error: undefined });
      this.next();
    }
  }

  cancel(key: string): void {
    const item = this.items().find((i) => i.key === key);
    if (!item) {
      return;
    }
    const runtime = this.runtime.get(key);
    if (item.status === 'downloading') {
      runtime?.job?.cancel();
      return;
    }
    if (runtime) {
      org.nativescript.audiobookplayer.Downloader.discard(Utils.android.getApplicationContext(), runtime.target);
    }
    this.remove(key);
    this.onChanged();
  }

  pauseAll(): void {
    this.items().forEach((i) => this.pause(i.key));
  }

  resumeAll(): void {
    this.items().forEach((i) => this.resume(i.key));
  }

  cancelAll(): void {
    this.items().forEach((i) => this.cancel(i.key));
  }

  private next(): void {
    const active = this.items().filter((i) => i.status === 'downloading').length;
    if (active >= MAX_PARALLEL) {
      return;
    }
    const item = this.items().find((i) => i.status === 'queued');
    if (item) {
      this.start(item);
    }
  }

  private start(item: DownloadItem): void {
    const runtime = this.runtime.get(item.key)!;
    this.patch(item.key, { status: 'downloading' });
    // Les callbacks natifs sont exécutés sur le thread principal.
    runtime.job = org.nativescript.audiobookplayer.Downloader.start(
      Utils.android.getApplicationContext(),
      runtime.url,
      runtime.target,
      new org.nativescript.audiobookplayer.Downloader.Callback({
        onProgress: (done: number, total: number) => {
          const now = Date.now();
          if (now - this.lastEmit >= PROGRESS_THROTTLE_MS) {
            this.lastEmit = now;
            this.patch(item.key, { done: Number(done), total: Number(total) });
          }
        },
        onDone: () => {
          this.podcasts.completeDownload(item.feedUrl, item.episodeId, runtime.target);
          this.finish(item.key);
        },
        onError: (message: string) => {
          this.patch(item.key, { status: 'error', error: String(message) });
          this.next();
        },
        onStopped: (cancelled: boolean) => {
          if (cancelled) {
            this.finish(item.key);
          } else {
            this.patch(item.key, { status: 'paused' });
            this.next();
          }
        },
      }),
    );
  }

  private finish(key: string): void {
    this.remove(key);
    this.onChanged();
    this.next();
  }

  private remove(key: string): void {
    this.runtime.delete(key);
    this.items.update((list) => list.filter((i) => i.key !== key));
  }

  private patch(key: string, changes: Partial<DownloadItem>): void {
    this.items.update((list) => list.map((i) => (i.key === key ? { ...i, ...changes } : i)));
  }
}
