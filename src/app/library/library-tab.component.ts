import { Component, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService } from '../core/app-state.service';
import { BookRowComponent } from './book-row.component';
import { CatalogService } from './catalog.service';
import { ContinueCardComponent } from './continue-card.component';

@Component({
  selector: 'app-library-tab',
  imports: [NativeScriptCommonModule, BookRowComponent, ContinueCardComponent],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './library-tab.component.html',
})
export class LibraryTabComponent {
  readonly catalog = inject(CatalogService);
  readonly state = inject(AppStateService);
}
