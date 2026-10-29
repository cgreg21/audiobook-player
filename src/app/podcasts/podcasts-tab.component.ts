import { Component, inject, NO_ERRORS_SCHEMA, signal } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService } from '../core/app-state.service';
import { BookRowComponent } from '../library/book-row.component';
import { CatalogService } from '../library/catalog.service';
import { PodcastResult, PodcastService } from '../library/podcast.service';

@Component({
  selector: 'app-podcasts-tab',
  imports: [NativeScriptCommonModule, BookRowComponent],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './podcasts-tab.component.html',
})
export class PodcastsTabComponent {
  readonly catalog = inject(CatalogService);
  readonly state = inject(AppStateService);
  private readonly podcasts = inject(PodcastService);
  readonly searching = signal(false);

  async search(term: string): Promise<void> {
    const query = term?.trim();
    if (!query) {
      return;
    }
    this.searching.set(true);
    try {
      this.catalog.searchResults.set(await this.podcasts.search(query));
      this.catalog.searched.set(true);
    } catch {
      this.state.showError('Recherche impossible', 'Vérifiez votre connexion et réessayez.');
    } finally {
      this.searching.set(false);
    }
  }

  isSubscribed(result: PodcastResult): boolean {
    this.catalog.books();
    return this.podcasts.isSubscribed(result.feedUrl);
  }

  async subscribe(result: PodcastResult): Promise<void> {
    await this.catalog.runBusy(result.feedUrl, async () => {
      await this.podcasts.subscribe(result);
      this.catalog.rebuild();
    }, 'Abonnement impossible', 'Le flux du podcast n’a pas pu être lu.');
  }
}
