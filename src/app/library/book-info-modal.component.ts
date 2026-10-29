import { Component, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService } from '../core/app-state.service';

@Component({
  selector: 'app-book-info-modal',
  imports: [NativeScriptCommonModule],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './book-info-modal.component.html',
})
export class BookInfoModalComponent {
  readonly state = inject(AppStateService);

  constructor() {
    this.state.backHandlers.push(() => {
      if (!this.state.infoBook()) {
        return false;
      }
      this.state.infoBook.set(null);
      return true;
    });
  }

  duration(minutes: number): string {
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    return hours ? `${hours} h ${String(rest).padStart(2, '0')} min` : `${rest} min`;
  }
}
