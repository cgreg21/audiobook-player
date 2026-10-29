import { Component, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { Dialogs } from '@nativescript/core';
import { AppStateService } from '../core/app-state.service';
import { BookRowComponent } from './book-row.component';
import { LibraryFilterService, SORTS } from './library-filter.service';

@Component({
  selector: 'app-library-all',
  imports: [NativeScriptCommonModule, BookRowComponent],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './library-all.component.html',
})
export class LibraryAllComponent {
  readonly state = inject(AppStateService);
  readonly filters = inject(LibraryFilterService);

  async pickSort(): Promise<void> {
    const result = await Dialogs.action({
      title: 'Trier par',
      cancelButtonText: 'Annuler',
      actions: SORTS.map((s) => s.label),
    });
    const choice = SORTS.find((s) => s.label === result);
    if (choice) this.filters.sort.set(choice.value);
  }
}
