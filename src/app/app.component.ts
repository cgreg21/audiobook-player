import { Component, NO_ERRORS_SCHEMA } from '@angular/core';
import { PageRouterOutlet } from '@nativescript/angular';
import { BookPickerComponent } from './library/book-picker.component';
import { LibraryFilterDrawerComponent } from './library/library-filter-drawer.component';
import { BookInfoModalComponent } from './library/book-info-modal.component';

@Component({
  selector: 'ns-app',
  templateUrl: './app.component.html',
  imports: [PageRouterOutlet, BookPickerComponent, LibraryFilterDrawerComponent, BookInfoModalComponent],
  schemas: [NO_ERRORS_SCHEMA],
})
export class AppComponent {}
