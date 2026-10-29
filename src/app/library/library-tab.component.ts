import { Component, computed, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService } from '../core/app-state.service';
import { BookRowComponent } from './book-row.component';
import { CatalogService } from './catalog.service';
import { ContinueCardComponent } from './continue-card.component';
import { LibraryAllComponent } from './library-all.component';

const RECENT_COUNT = 5;

@Component({
  selector: 'app-library-tab',
  imports: [NativeScriptCommonModule, BookRowComponent, ContinueCardComponent, LibraryAllComponent],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './library-tab.component.html',
})
export class LibraryTabComponent {
  readonly catalog = inject(CatalogService);
  readonly state = inject(AppStateService);

  // Les derniers éléments ajoutés, du plus récent au plus ancien.
  readonly recent = computed(() =>
    [...this.catalog.books()]
      .sort((a, b) => this.catalog.addedAt(b) - this.catalog.addedAt(a))
      .slice(0, RECENT_COUNT),
  );

}
