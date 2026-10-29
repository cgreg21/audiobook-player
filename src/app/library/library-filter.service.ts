import { computed, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { CatalogService } from './catalog.service';
import { Book } from './library.service';

export type SortKey = 'title-asc' | 'title-desc' | 'added-asc' | 'added-desc';
export type ReadState = 'read' | 'unread';

export const SORTS: { value: SortKey; label: string }[] = [
  { value: 'title-asc', label: 'Alphabétique croissant' },
  { value: 'title-desc', label: 'Alphabétique décroissant' },
  { value: 'added-asc', label: 'Date d’ajout croissante' },
  { value: 'added-desc', label: 'Date d’ajout décroissante' },
];

const byTitle = (a: Book, b: Book): number =>
  a.title.localeCompare(b.title, undefined, { numeric: true, sensitivity: 'base' });

function distinct(values: (string | undefined)[]): string[] {
  return [...new Set(values.filter((v): v is string => !!v))].sort((a, b) =>
    a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' }),
  );
}

// Filtres cumulables : entre types = ET, à l'intérieur d'un type = OU.
@Injectable({ providedIn: 'root' })
export class LibraryFilterService {
  private readonly catalog = inject(CatalogService);

  readonly sort = signal<SortKey>('title-asc');
  readonly sagas = signal<ReadonlySet<string>>(new Set());
  readonly authors = signal<ReadonlySet<string>>(new Set());
  readonly genres = signal<ReadonlySet<string>>(new Set());
  readonly readStates = signal<ReadonlySet<ReadState>>(new Set());
  readonly drawerOpen = signal(false);

  readonly sortLabel = computed(() => SORTS.find((s) => s.value === this.sort())!.label);
  readonly activeCount = computed(
    () => this.sagas().size + this.authors().size + this.genres().size + this.readStates().size,
  );
  readonly sagaOptions = computed(() => distinct(this.catalog.books().map((b) => b.series)));
  readonly authorOptions = computed(() => distinct(this.catalog.books().map((b) => b.author)));
  readonly genreOptions = computed(() => distinct(this.catalog.books().flatMap((b) => b.genres ?? [])));

  readonly results = computed(() => {
    const sagas = this.sagas();
    const authors = this.authors();
    const genres = this.genres();
    const reads = this.readStates();
    const books = this.catalog.books().filter((b) => {
      const state: ReadState = this.catalog.isRead(b) ? 'read' : 'unread';
      return (
        (!sagas.size || (!!b.series && sagas.has(b.series))) &&
        (!authors.size || (!!b.author && authors.has(b.author))) &&
        (!genres.size || (b.genres ?? []).some((g) => genres.has(g))) &&
        (!reads.size || reads.has(state))
      );
    });
    const added = new Map<string, number>(books.map((b): [string, number] => [b.id, this.catalog.addedAt(b)]));
    const byAdded = (a: Book, b: Book): number => added.get(a.id)! - added.get(b.id)!;
    const compare: (a: Book, b: Book) => number = {
      'title-asc': byTitle,
      'title-desc': (a, b) => byTitle(b, a),
      'added-asc': byAdded,
      'added-desc': (a, b) => byAdded(b, a),
    }[this.sort()];
    return books.sort(compare);
  });

  toggle<T>(target: WritableSignal<ReadonlySet<T>>, value: T): void {
    target.update((set) => {
      const next = new Set(set);
      if (next.has(value)) {
        next.delete(value);
      } else {
        next.add(value);
      }
      return next;
    });
  }

  reset(): void {
    this.sagas.set(new Set());
    this.authors.set(new Set());
    this.genres.set(new Set());
    this.readStates.set(new Set());
  }
}
