import { Component, computed, inject, NO_ERRORS_SCHEMA, signal } from '@angular/core';
import { Dialogs } from '@nativescript/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService } from '../core/app-state.service';
import { BookRowComponent } from './book-row.component';
import { CatalogService } from './catalog.service';
import { ContinueCardComponent } from './continue-card.component';

type MediaFilter = 'all' | 'book' | 'podcast';

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
    return this.catalog.books().filter((b) => filter === 'all' || (filter === 'podcast') === (b.kind === 'podcast'));
  });
}
