import { computed, Injectable, signal } from '@angular/core';
import { Application, Connectivity, Dialogs, Screen } from '@nativescript/core';
import { Book } from '../library/library.service';

export type Tab = 'library' | 'podcasts' | 'player' | 'options';

const TABLET_MIN_DIP = 600;
const TABLET_CONTENT_WIDTH = 680;

@Injectable({ providedIn: 'root' })
export class AppStateService {
  readonly activeTab = signal<Tab>('library');
  readonly selectedBook = signal<Book | null>(null);
  private readonly history: { tab: Tab; book: Book | null }[] = [];
  // Gestionnaires de retour prioritaires (ex. tiroir du lecteur ouvert).
  readonly backHandlers: (() => boolean)[] = [];
  readonly landscape = signal(Application.orientation() === 'landscape');
  readonly tablet = signal(
    Math.min(Screen.mainScreen.widthDIPs, Screen.mainScreen.heightDIPs) >= TABLET_MIN_DIP,
  );
  readonly wide = computed(() => this.landscape() || this.tablet());
  readonly contentWidth = computed(() => (this.tablet() ? TABLET_CONTENT_WIDTH : 'auto'));
  readonly contentAlign = computed(() => (this.tablet() ? 'center' : 'stretch'));
  readonly online = signal(Connectivity.getConnectionType() !== Connectivity.connectionType.none);

  constructor() {
    Connectivity.startMonitoring((type) => {
      this.online.set(type !== Connectivity.connectionType.none);
    });
    Application.on(Application.orientationChangedEvent, (args) => {
      this.landscape.set(args.newValue === 'landscape');
    });
  }

  setTab(tab: Tab): void {
    this.pushHistory();
    this.activeTab.set(tab);
    this.selectedBook.set(null);
  }

  openBook(book: Book): void {
    this.pushHistory();
    this.activeTab.set('library');
    this.selectedBook.set(book);
  }

  closeBook(): void {
    this.selectedBook.set(null);
  }

  // Retourne true si le retour a été géré dans l'appli, false pour laisser Android quitter.
  goBack(): boolean {
    if (this.backHandlers.some((handler) => handler())) {
      return true;
    }
    const previous = this.history.pop();
    if (!previous) {
      if (this.activeTab() !== 'library' || this.selectedBook()) {
        this.activeTab.set('library');
        this.selectedBook.set(null);
        return true;
      }
      return false;
    }
    this.activeTab.set(previous.tab);
    this.selectedBook.set(previous.book);
    return true;
  }

  private pushHistory(): void {
    const last = this.history[this.history.length - 1];
    const entry = { tab: this.activeTab(), book: this.selectedBook() };
    if (last && last.tab === entry.tab && last.book === entry.book) {
      return;
    }
    this.history.push(entry);
    if (this.history.length > 30) {
      this.history.shift();
    }
  }

  showError(title: string, message: string): void {
    Dialogs.alert({ title, message, okButtonText: 'OK' });
  }
}
