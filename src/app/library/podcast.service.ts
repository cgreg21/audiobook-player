import { inject, Injectable } from '@angular/core';
import { File, Http, ParserEvent, ParserEventType, Utils, XmlParser } from '@nativescript/core';
import { DatabaseService } from '../data/database.service';
import { SettingsService } from '../settings/settings.service';
import { Book, LibraryService } from './library.service';

declare const org: any;
declare const java: any;

export interface Episode {
  id: string;
  title: string;
  remoteUrl: string;
  localPath?: string;
}

export interface Podcast {
  feedUrl: string;
  title: string;
  author: string;
  artwork?: string;
  episodes: Episode[];
}

export interface PodcastResult {
  feedUrl: string;
  title: string;
  author: string;
  artwork?: string;
}


@Injectable({ providedIn: 'root' })
export class PodcastService {
  private readonly db = inject(DatabaseService);
  private readonly settings = inject(SettingsService);
  private readonly library = inject(LibraryService);
  private podcasts: Podcast[] = this.db.loadPodcasts();

  async search(term: string): Promise<PodcastResult[]> {
    const url = `https://itunes.apple.com/search?media=podcast&entity=podcast&limit=20&term=${encodeURIComponent(term)}`;
    const json: any = await Http.getJSON(url);
    return (json.results ?? [])
      .filter((r: any) => r.feedUrl)
      .map((r: any) => ({
        feedUrl: r.feedUrl,
        title: r.collectionName,
        author: r.artistName,
        artwork: r.artworkUrl100,
      }));
  }

  isSubscribed(feedUrl: string): boolean {
    return this.podcasts.some((p) => p.feedUrl === feedUrl);
  }

  async subscribe(result: PodcastResult): Promise<void> {
    if (this.isSubscribed(result.feedUrl)) {
      return;
    }
    const episodes = await this.fetchEpisodes(result.feedUrl);
    const podcast: Podcast = { ...result, episodes };
    this.podcasts.push(podcast);
    this.save(podcast);
  }

  async refresh(feedUrl: string): Promise<void> {
    const podcast = this.podcasts.find((p) => p.feedUrl === feedUrl);
    if (!podcast) {
      return;
    }
    const fresh = await this.fetchEpisodes(feedUrl);
    const downloaded = new Map(podcast.episodes.filter((e) => e.localPath).map((e) => [e.id, e.localPath]));
    podcast.episodes = fresh.map((e) => ({ ...e, localPath: downloaded.get(e.id) }));
    this.save(podcast);
  }

  unsubscribe(feedUrl: string): void {
    const podcast = this.podcasts.find((p) => p.feedUrl === feedUrl);
    podcast?.episodes.forEach((e) => this.deleteFile(e.localPath));
    this.podcasts = this.podcasts.filter((p) => p.feedUrl !== feedUrl);
    this.db.deletePodcast(feedUrl);
  }

  async download(feedUrl: string, episodeId: string): Promise<void> {
    const episode = this.findEpisode(feedUrl, episodeId);
    if (!episode || episode.localPath) {
      return;
    }
    const podcast = this.podcasts.find((p) => p.feedUrl === feedUrl);
    const feedName = this.safeName(podcast?.title) || this.hash(feedUrl);
    const extension = /\.(mp3|m4a|aac|ogg)(\?|$)/i.exec(episode.remoteUrl)?.[1] ?? 'mp3';
    const fileName = `${this.safeName(episode.title) || 'episode'}-${this.hash(episode.id)}.${extension}`;
    const shared = this.library.createPodcastFile(feedName, fileName, 'audio/mpeg');
    if (shared) {
      await this.streamToUri(episode.remoteUrl, shared);
      episode.localPath = shared;
    } else {
      const folder = new java.io.File(this.settings.downloadsRoot(), feedName);
      folder.mkdirs();
      const target = `${folder.getAbsolutePath()}/${fileName}`;
      await this.streamToFile(episode.remoteUrl, target);
      episode.localPath = target;
    }
    this.save(podcast!);
  }

  private streamToUri(url: string, targetUri: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      org.nativescript.audiobookplayer.Downloader.downloadToUri(
        Utils.android.getApplicationContext(),
        url,
        targetUri,
        new org.nativescript.audiobookplayer.Downloader.Callback({
          onDone: () => resolve(),
          onError: (message: string) => reject(new Error(message)),
        }),
      );
    });
  }

  private isContent(path: string): boolean {
    return path.startsWith('content://');
  }

  private contentSize(path: string): number | null {
    try {
      const fd = Utils.android
        .getApplicationContext()
        .getContentResolver()
        .openFileDescriptor(android.net.Uri.parse(path), 'r');
      const size = Number(fd.getStatSize());
      fd.close();
      return size;
    } catch {
      return null;
    }
  }

  // Native thread: NativeScript JS callbacks run on the main thread, and Http.getFile buffers the whole body in memory.
  private streamToFile(url: string, target: string): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      org.nativescript.audiobookplayer.Downloader.download(
        url,
        target,
        new org.nativescript.audiobookplayer.Downloader.Callback({
          onDone: () => resolve(),
          onError: (message: string) => reject(new Error(message)),
        }),
      );
    });
  }

  removeDownload(feedUrl: string, episodeId: string): void {
    const episode = this.findEpisode(feedUrl, episodeId);
    if (episode) {
      this.deleteFile(episode.localPath);
      episode.localPath = undefined;
      this.save(this.podcasts.find((p) => p.feedUrl === feedUrl)!);
    }
  }

  usage(): { title: string; episodes: number; bytes: number }[] {
    return this.podcasts.map((p) => {
      const sizes = p.episodes
        .filter((e) => e.localPath)
        .map((e) => (this.isContent(e.localPath!) ? this.contentSize(e.localPath!) : File.exists(e.localPath!) ? Number(new java.io.File(e.localPath!).length()) : null))
        .filter((s): s is number => s !== null);
      return { title: p.title, episodes: sizes.length, bytes: sizes.reduce((sum, s) => sum + s, 0) };
    });
  }

  clearDownloads(): void {
    for (const podcast of this.podcasts) {
      podcast.episodes.forEach((e) => {
        this.deleteFile(e.localPath);
        e.localPath = undefined;
      });
      this.save(podcast);
    }
  }

  toBooks(): Book[] {
    return this.podcasts.map((p) => ({
      id: p.feedUrl,
      title: p.title,
      kind: 'podcast' as const,
      artwork: p.artwork,
      tracks: p.episodes.map((e) => ({
        id: e.id,
        name: e.title,
        uri: e.localPath ?? '',
        remoteUrl: e.remoteUrl,
        downloaded: !!e.localPath,
      })),
    }));
  }

  private findEpisode(feedUrl: string, episodeId: string): Episode | undefined {
    return this.podcasts.find((p) => p.feedUrl === feedUrl)?.episodes.find((e) => e.id === episodeId);
  }

  private async fetchEpisodes(feedUrl: string): Promise<Episode[]> {
    const xml = await Http.getString(feedUrl);
    const episodes: Episode[] = [];
    let current: { title: string; guid: string; url: string } | null = null;
    let field: 'title' | 'guid' | null = null;

    const parser = new XmlParser((event: ParserEvent) => {
      const name = event.elementName;
      if (event.eventType === ParserEventType.StartElement) {
        if (name === 'item') {
          current = { title: '', guid: '', url: '' };
        } else if (current && name === 'enclosure') {
          current.url = (event.attributes as any)?.url ?? '';
        } else if (current && (name === 'title' || name === 'guid')) {
          field = name;
        }
      } else if (event.eventType === ParserEventType.EndElement) {
        if (name === 'item' && current) {
          if (current.url) {
            episodes.push({
              id: current.guid || current.url,
              title: current.title.trim() || 'Épisode',
              remoteUrl: current.url,
            });
          }
          current = null;
        }
        field = null;
      } else if (
        current &&
        field &&
        (event.eventType === ParserEventType.Text || event.eventType === ParserEventType.CDATA)
      ) {
        current[field] += event.data;
      }
    });
    parser.parse(xml);
    return episodes.reverse();
  }

  private deleteFile(path?: string): void {
    if (path && this.isContent(path)) {
      try {
        android.provider.DocumentsContract.deleteDocument(
          Utils.android.getApplicationContext().getContentResolver(),
          android.net.Uri.parse(path),
        );
      } catch {
        // Already deleted or access revoked.
      }
    } else if (path && File.exists(path)) {
      File.fromPath(path).removeSync();
    }
  }

  private safeName(value?: string): string {
    return (value ?? '').replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 60);
  }

  private hash(value: string): string {
    let h = 0;
    for (let i = 0; i < value.length; i++) {
      h = (Math.imul(31, h) + value.charCodeAt(i)) | 0;
    }
    return (h >>> 0).toString(36);
  }

  private save(podcast: Podcast): void {
    this.db.savePodcast(podcast, this.podcasts.indexOf(podcast));
  }
}

