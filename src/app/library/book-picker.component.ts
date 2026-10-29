import { Component, inject, NO_ERRORS_SCHEMA, signal } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { BookPickerService } from './book-picker.service';

@Component({
  selector: 'app-book-picker',
  imports: [NativeScriptCommonModule],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './book-picker.component.html',
})
export class BookPickerComponent {
  readonly picker = inject(BookPickerService);
  readonly selected = signal<string | null>(null);

  select(asin: string): void {
    this.selected.set(asin);
  }

  validate(): void {
    this.close(this.selected());
  }

  cancel(): void {
    this.close(null);
  }

  private close(asin: string | null): void {
    this.selected.set(null);
    this.picker.close(asin);
  }
}
