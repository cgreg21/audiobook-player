import { Component, inject, NO_ERRORS_SCHEMA, OnInit } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { PlayerService } from '../player/player.service';
import { PressDirective } from '../core/motion.directives';
import { CatalogService } from './catalog.service';
import { Book } from './library.service';

@Component({
  selector: 'app-continue-card',
  imports: [NativeScriptCommonModule, PressDirective],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './continue-card.component.html',
})
export class ContinueCardComponent implements OnInit {
  readonly catalog = inject(CatalogService);
  private readonly player = inject(PlayerService);

  ngOnInit(): void {
    this.catalog.refreshContinue();
  }

  columns(book: Book): string {
    const fraction = this.player.bookFraction(book);
    return `${Math.max(fraction, 0.001)}*, ${Math.max(1 - fraction, 0.001)}*`;
  }

  trackName(book: Book): string {
    const live = this.player.book()?.id === book.id;
    const index = live ? this.player.trackIndex() : this.player.resumeProgress(book).index;
    return book.tracks[Math.min(index, book.tracks.length - 1)]?.name ?? '';
  }
}
