import { Component, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService } from '../core/app-state.service';
import { LibraryFilterService } from './library-filter.service';

@Component({
  selector: 'app-library-filter-drawer',
  imports: [NativeScriptCommonModule],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './library-filter-drawer.component.html',
})
export class LibraryFilterDrawerComponent {
  readonly filters = inject(LibraryFilterService);
  private readonly state = inject(AppStateService);

  constructor() {
    this.state.backHandlers.push(() => {
      if (!this.filters.drawerOpen()) {
        return false;
      }
      this.filters.drawerOpen.set(false);
      return true;
    });
  }
}
