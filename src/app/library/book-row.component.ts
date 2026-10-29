import { Component, inject, input, NO_ERRORS_SCHEMA, output } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { CatalogService } from '../library/catalog.service';
import { Book } from '../library/library.service';
import { PressDirective } from '../core/motion.directives';

@Component({
  selector: 'app-book-row',
  imports: [NativeScriptCommonModule, PressDirective],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './book-row.component.html',
})
export class BookRowComponent {
  readonly catalog = inject(CatalogService);
  readonly book = input.required<Book>();
  readonly opened = output<void>();
}
