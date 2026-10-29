import { computed, inject, Injectable, signal } from '@angular/core';
import { Application } from '@nativescript/core';
import { AppStateService } from '../core/app-state.service';
import { DatabaseService } from '../data/database.service';
import { PlayerService } from '../player/player.service';
import { Book, BookTags, LibraryService } from './library.service';
import { BookInfoService } from './book-info.service';
import { PodcastResult, PodcastService } from './podcast.service';
import { DownloadQueueService } from './download-queue.service';
import { SettingsService } from '../settings/settings.service';

const NEW_DAYS = 4;

@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly database = inject(DatabaseService);
  private readonly settings = inject(SettingsService);
  private readonly queue = inject(DownloadQueueService);
  private readonly library = inject(LibraryService);
  private readonly bookInfo = inject(BookInfoService);
  private readonly podcasts = inject(PodcastService);
  private readonly player = inject(PlayerService);
  private readonly state = inject(AppStateService);

  private folderBooks: Book[] = this.library.loadBooks();

  readonly books = signal<Book[]>([...this.folderBooks, ...this.podcasts.toBooks()]);
  readonly subscribedBooks = computed(() => this.books().filter((b) => b.kind === 'podcast'));
  readonly continueBook = signal<Book | null>(null);
  readonly busy = signal<ReadonlySet<string>>(new Set());
  readonly searchResults = signal<PodcastResult[]>([]);
  readonly searched = signal(false);
  readonly refreshing = signal(false);
  readonly libraryProgress = signal<{ done: number; total: number; label: string } | null>(null);
  private updatingLibrary = false;

  constructor() {
    this.refreshContinue();
    this.queue.onChanged = () => this.rebuild(this.state.selectedBook()?.id);
    Application.on(Application.resumeEvent, () => {
      this.refreshFolders();
      this.refreshFeedsIfDue();
    });
    setTimeout(() => this.refreshFeeds(), 1500);
    setTimeout(() => void this.updateLibrary(), 800);
    setInterval(() => this.refreshFeedsIfDue(), 60_000);
  }

  // Lit les tags puis complète en ligne (Audnexus, Open Library, iTunes) ce qui manque ; `force` ignore le cache.
  async updateLibrary(force = false): Promise<void> {
    if (this.updatingLibrary) {
      return;
    }
    this.updatingLibrary = true;
    try {
      const pending = this.folderBooks.filter((book) => force || this.needsInfo(book));
      for (let i = 0; i < pending.length; i++) {
        const book = pending[i];
        this.libraryProgress.set({ done: i, total: pending.length, label: book.title });
        try {
          const tags = await this.collectInfo(book);
          this.library.saveTags(book, tags);
          this.folderBooks = this.folderBooks.map((b) => (b.id === book.id ? this.library.applyTags(b, tags) : b));
          this.rebuild(this.state.selectedBook()?.id);
        } catch {
          // Livre ignoré : il sera retenté au prochain passage.
        }
      }
      if (pending.length) {
        this.libraryProgress.set({ done: pending.length, total: pending.length, label: '' });
        await new Promise((resolve) => setTimeout(resolve, 600));
      }
    } finally {
      this.libraryProgress.set(null);
      this.updatingLibrary = false;
    }
  }

  private needsInfo(book: Book): boolean {
    const tags = this.library.cachedTags(book);
    return !tags || (!tags.online && (!tags.artwork || !tags.author) && this.state.online());
  }

  private async collectInfo(book: Book): Promise<BookTags> {
    const cached = this.library.cachedTags(book);
    const tags = cached ?? (await this.library.readTags(book));
    const needAuthor = !tags.author;
    const needCover = !tags.artwork;
    if ((needAuthor || needCover) && this.state.online()) {
      const info = await this.bookInfo.lookup(tags.title || book.title, tags.author, { author: needAuthor, cover: needCover });
      tags.author ||= info.author;
      if (needCover && info.coverUrl && (await this.bookInfo.downloadCover(info.coverUrl, this.library.coverPath(book.id)))) {
        tags.artwork = this.library.coverPath(book.id);
      }
      tags.online = true;
    }
    return tags;
  }

  async refreshFeeds(): Promise<void> {
    if (this.refreshing() || !this.state.online() || !this.podcasts.toBooks().length) {
      return;
    }
    this.refreshing.set(true);
    try {
      for (const book of this.podcasts.toBooks()) {
        try {
          await this.podcasts.refresh(book.id);
        } catch {
          // flux injoignable : on passe au suivant
        }
      }
      this.database.setSetting('feeds.last', String(Date.now()));
      this.rebuild();
    } finally {
      this.refreshing.set(false);
    }
  }

  private refreshFeedsIfDue(): void {
    const minutes = this.settings.feedInterval();
    const last = Number(this.database.getSetting('feeds.last', '0'));
    if (minutes > 0 && Date.now() - last >= minutes * 60_000) {
      void this.refreshFeeds();
    }
  }

  // Rescan des dossiers au retour dans l'app ; ne remplace la liste que si elle a changé.
  private refreshFolders(): void {
    try {
      const books = this.library.loadBooks();
      const signature = (list: Book[]) => list.map((b) => `${b.id}:${b.tracks.length}`).join('|');
      if (signature(books) !== signature(this.folderBooks)) {
        this.folderBooks = books;
        this.rebuild(this.state.selectedBook()?.id);
        void this.updateLibrary();
      }
    } catch {
      // dossier momentanément inaccessible : on garde la liste actuelle
    }
  }

  rebuild(selectedId?: string): void {
    this.books.set([...this.folderBooks, ...this.podcasts.toBooks()]);
    if (selectedId) {
      this.state.selectedBook.set(this.books().find((b) => b.id === selectedId) ?? null);
    }
    this.refreshContinue();
  }

  reloadFolders(): void {
    this.folderBooks = this.library.loadBooks();
    this.rebuild();
    void this.updateLibrary(true);
  }

  async importFolder(): Promise<boolean> {
    try {
      this.folderBooks = await this.library.importFolder();
      this.rebuild();
      void this.updateLibrary();
      return true;
    } catch {
      this.state.showError('Import impossible', 'Le dossier sélectionné n’a pas pu être lu.');
      return false;
    }
  }

  refreshContinue(): void {
    const id = this.player.book()?.id ?? this.player.lastPlayedBookId();
    this.continueBook.set(this.books().find((b) => b.id === id && b.tracks.length) ?? null);
  }

  async play(book: Book, trackIndex?: number): Promise<void> {
    try {
      await this.player.playBook(book, trackIndex);
      this.state.activeTab.set('player');
    } catch {
      this.state.showError('Lecture impossible', 'Ce fichier audio n’a pas pu être lu.');
    }
  }

  isBusy(key: string): boolean {
    return this.busy().has(key);
  }

  async runBusy(key: string, action: () => Promise<void>, errorTitle: string, errorMessage: string): Promise<void> {
    this.busy.update((set) => new Set(set).add(key));
    try {
      await action();
    } catch (error) {
      console.error(`${errorTitle}:`, error instanceof Error ? error.stack ?? error.message : String(error));
      this.state.showError(errorTitle, `${errorMessage}\n${error instanceof Error ? error.message : String(error)}`);
    } finally {
      this.busy.update((set) => {
        const next = new Set(set);
        next.delete(key);
        return next;
      });
    }
  }

  downloadedCount(book: Book): number {
    return book.tracks.filter((t) => t.downloaded).length;
  }

  bookSummary(book: Book): string {
    const count = book.tracks.length;
    if (book.kind === 'podcast') {
      return `Podcast · ${this.downloadedCount(book)}/${count} téléchargé${count > 1 ? 's' : ''}`;
    }
    const files = `${count} fichier${count > 1 ? 's' : ''}`;
    return book.author ? `${book.author} · ${files}` : files;
  }

  // Terminé / en cours / nouveau (ajouté depuis moins de 4 jours et jamais lancé).
  bookStatus(book: Book): 'done' | 'progress' | null {
    const fraction = this.player.bookFraction(book);
    if (fraction >= 1) {
      return 'done';
    }
    const saved = this.player.resumeProgress(book);
    if (fraction > 0 || saved.positionMs > 0 || saved.index > 0 || this.player.book()?.id === book.id) {
      return 'progress';
    }
    return null;
  }

  // Un podcast avec de nouveaux épisodes garde l'icône en plus de son statut ; un livre seulement tant qu'il n'est pas commencé.
  isNew(book: Book): boolean {
    if (book.kind === 'podcast' && this.newEpisodesAt(book) > 0) {
      return true;
    }
    return !this.bookStatus(book) && Date.now() - this.addedAt(book) < NEW_DAYS * 86_400_000;
  }

  // Date du dernier rafraîchissement du flux ayant apporté de nouveaux épisodes, si < 4 jours.
  private newEpisodesAt(book: Book): number {
    const at = Number(this.database.getSetting(`newep:${book.id}`, '0'));
    return Date.now() - at < NEW_DAYS * 86_400_000 ? at : 0;
  }

  private addedAt(book: Book): number {
    if (book.addedAt) {
      return book.addedAt;
    }
    const key = `seen:${book.id}`;
    const stored = Number(this.database.getSetting(key, '0'));
    if (stored > 0) {
      return stored;
    }
    const now = Date.now();
    this.database.setSetting(key, String(now));
    return now;
  }

  resumeLabel(book: Book): string {
    const saved = this.player.resumeProgress(book);
    return saved.positionMs > 0 || saved.index > 0 ? '▶  Reprendre' : '▶  Lire';
  }
}
