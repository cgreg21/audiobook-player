import { Component, computed, inject, NO_ERRORS_SCHEMA, signal } from '@angular/core';
import { Dialogs } from '@nativescript/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService } from '../core/app-state.service';
import { BookRowComponent } from './book-row.component';
import { CatalogService } from './catalog.service';
import { Book } from './library.service';
import { ContinueCardComponent } from './continue-card.component';

type MediaFilter = 'all' | 'book' | 'podcast';
type GroupBy = 'none' | 'author' | 'narrator' | 'series' | 'genre' | 'publisher' | 'type';

const UNKNOWN_GROUP = 'Non renseigné';

@Component({
  selector: 'app-library-tab',
  imports: [NativeScriptCommonModule, BookRowComponent, ContinueCardComponent],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './library-tab.component.html',
})
export class LibraryTabComponent {
  readonly catalog = inject(CatalogService);
  readonly state = inject(AppStateService);

  readonly filters: { value: MediaFilter; label: string }[] = [
    { value: 'all', label: 'Tous' },
    { value: 'book', label: 'Livres' },
    { value: 'podcast', label: 'Podcasts' },
  ];
  readonly filter = signal<MediaFilter>('all');
  readonly filterLabel = computed(() => this.filters.find((f) => f.value === this.filter())!.label);

  async pickFilter(): Promise<void> {
    const result = await Dialogs.action({
      title: 'Type de média',
      cancelButtonText: 'Annuler',
      actions: this.filters.map((f) => f.label),
    });
    const choice = this.filters.find((f) => f.label === result);
    if (choice) this.filter.set(choice.value);
  }

  readonly visibleBooks = computed(() => {
    const filter = this.filter();
    return this.catalog
      .books()
      .filter((b) => filter === 'all' || (filter === 'podcast') === (b.kind === 'podcast'))
      .sort((a, b) => a.title.localeCompare(b.title, undefined, { numeric: true, sensitivity: 'base' }));
  });

  readonly groupings: { value: GroupBy; label: string }[] = [
    { value: 'none', label: 'Aucun' },
    { value: 'author', label: 'Auteur' },
    { value: 'narrator', label: 'Narrateur' },
    { value: 'series', label: 'Saga' },
    { value: 'genre', label: 'Catégorie' },
    { value: 'publisher', label: 'Éditeur' },
    { value: 'type', label: 'Type' },
  ];
  readonly grouping = signal<GroupBy>('none');
  readonly groupingLabel = computed(() => this.groupings.find((g) => g.value === this.grouping())!.label);

  async pickGrouping(): Promise<void> {
    const result = await Dialogs.action({
      title: 'Regrouper par',
      cancelButtonText: 'Annuler',
      actions: this.groupings.map((g) => g.label),
    });
    const choice = this.groupings.find((g) => g.label === result);
    if (choice) this.grouping.set(choice.value);
  }

  readonly groups = computed(() => {
    const by = this.grouping();
    const books = this.visibleBooks();
    if (by === 'none') return [{ label: '', books }];
    const map = new Map<string, Book[]>();
    for (const book of books) {
      for (const key of this.groupKeys(book, by)) {
        const list = map.get(key);
        if (list) list.push(book);
        else map.set(key, [book]);
      }
    }
    return [...map.entries()]
      .sort(
        ([a], [b]) =>
          Number(a === UNKNOWN_GROUP) - Number(b === UNKNOWN_GROUP) ||
          a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' }),
      )
      .map(([label, groupBooks]) => ({ label, books: groupBooks }));
  });

  private groupKeys(book: Book, by: Exclude<GroupBy, 'none'>): string[] {
    switch (by) {
      case 'author':
        return [book.author || UNKNOWN_GROUP];
      case 'narrator':
        return book.narrators?.length ? book.narrators : [UNKNOWN_GROUP];
      case 'series':
        return [book.series || UNKNOWN_GROUP];
      case 'genre':
        return book.genres?.length ? book.genres : [UNKNOWN_GROUP];
      case 'publisher':
        return [book.publisher || UNKNOWN_GROUP];
      case 'type':
        return [book.kind === 'podcast' ? 'Podcasts' : 'Livres'];
    }
  }
}
