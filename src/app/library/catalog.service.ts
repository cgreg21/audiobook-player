import { computed, inject, Injectable, signal } from '@angular/core';
import { AppStateService } from '../core/app-state.service';
import { PlayerService } from '../player/player.service';
import { Book, LibraryService } from './library.service';
import { PodcastResult, PodcastService } from './podcast.service';

@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly library = inject(LibraryService);
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

  constructor() {
    this.refreshContinue();
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
  }

  async importFolder(): Promise<boolean> {
    try {
      this.folderBooks = await this.library.importFolder();
      this.rebuild();
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
    return `${count} fichier${count > 1 ? 's' : ''} MP3`;
  }

  resumeLabel(book: Book): string {
    const saved = this.player.resumeProgress(book);
    return saved.positionMs > 0 || saved.index > 0 ? '▶  Reprendre' : '▶  Lire';
  }
}
