import { Injectable, signal } from '@angular/core';
import { Application, Dialogs } from '@nativescript/core';
import { Book } from '../library/library.service';

export type Tab = 'library' | 'podcasts' | 'player' | 'options';

@Injectable({ providedIn: 'root' })
export class AppStateService {
  readonly activeTab = signal<Tab>('library');
  readonly selectedBook = signal<Book | null>(null);
  readonly landscape = signal(Application.orientation() === 'landscape');

  constructor() {
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
