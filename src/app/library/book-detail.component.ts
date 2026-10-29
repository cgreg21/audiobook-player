import { Component, inject, input, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { Dialogs } from '@nativescript/core';
import { AppStateService } from '../core/app-state.service';
import { PlayerService } from '../player/player.service';
import { CatalogService } from './catalog.service';
import { Book, Track } from './library.service';
import { PodcastService } from './podcast.service';

@Component({
  selector: 'app-book-detail',
  imports: [NativeScriptCommonModule],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './book-detail.component.html',
})
export class BookDetailComponent {
  readonly book = input.required<Book>();
  readonly catalog = inject(CatalogService);
  readonly state = inject(AppStateService);
  readonly player = inject(PlayerService);
  private readonly podcasts = inject(PodcastService);

  async refresh(): Promise<void> {
    const id = this.book().id;
    await this.catalog.runBusy(id, async () => {
      await this.podcasts.refresh(id);
      this.catalog.rebuild(id);
    }, 'Actualisation impossible', 'Le flux du podcast n’a pas pu être lu.');
  }

  async unsubscribe(): Promise<void> {
    const book = this.book();
    const confirmed = await Dialogs.confirm({
      title: 'Supprimer le podcast',
      message: `Supprimer « ${book.title} » et ses épisodes téléchargés ?`,
      okButtonText: 'Supprimer',
      cancelButtonText: 'Annuler',
    });
    if (confirmed) {
      this.podcasts.unsubscribe(book.id);
      this.state.closeBook();
      this.catalog.rebuild();
    }
  }

  async toggleDownload(track: Track): Promise<void> {
    const id = this.book().id;
    if (track.downloaded) {
      this.podcasts.removeDownload(id, track.id);
      this.catalog.rebuild(id);
      return;
    }
    await this.catalog.runBusy(`${id}|${track.id}`, async () => {
      await this.podcasts.download(id, track.id);
      this.catalog.rebuild(id);
    }, 'Téléchargement impossible', 'L’épisode n’a pas pu être téléchargé.');
  }
}
