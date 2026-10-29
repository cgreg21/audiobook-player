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
    this.activeTab.set(tab);
    this.selectedBook.set(null);
  }

  openBook(book: Book): void {
    this.activeTab.set('library');
    this.selectedBook.set(book);
  }

  closeBook(): void {
    this.selectedBook.set(null);
  }

  showError(title: string, message: string): void {
    Dialogs.alert({ title, message, okButtonText: 'OK' });
  }
}
