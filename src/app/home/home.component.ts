import { Component, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { Page } from '@nativescript/core';
import { AppStateService } from '../core/app-state.service';
import { EnterDirective } from '../core/motion.directives';
import { BookDetailComponent } from '../library/book-detail.component';
import { LibraryTabComponent } from '../library/library-tab.component';
import { OptionsTabComponent } from '../options/options-tab.component';
import { PlayerTabComponent } from '../player/player-tab.component';
import { PodcastsTabComponent } from '../podcasts/podcasts-tab.component';
import { SettingsService } from '../settings/settings.service';
import { BottomNavComponent } from './bottom-nav.component';
import { MiniPlayerComponent } from './mini-player.component';
import { TopBarComponent } from './top-bar.component';

@Component({
  selector: 'Home',
  templateUrl: './home.component.html',
  imports: [
    NativeScriptCommonModule,
    TopBarComponent,
    BottomNavComponent,
    MiniPlayerComponent,
    EnterDirective,
    LibraryTabComponent,
    BookDetailComponent,
    PodcastsTabComponent,
    OptionsTabComponent,
    PlayerTabComponent,
  ],
  schemas: [NO_ERRORS_SCHEMA],
})
export class HomeComponent {
  readonly settings = inject(SettingsService);
  readonly state = inject(AppStateService);

  constructor() {
    inject(Page).actionBarHidden = true;
  }
}
