import { Component, effect, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { Application, Color, Page } from '@nativescript/core';
import { AppStateService } from '../core/app-state.service';
import { EnterDirective } from '../core/motion.directives';
import { BookDetailComponent } from '../library/book-detail.component';
import { LibraryTabComponent } from '../library/library-tab.component';
import { OptionsTabComponent } from '../options/options-tab.component';
import { PlayerTabComponent } from '../player/player-tab.component';
import { PodcastsTabComponent } from '../podcasts/podcasts-tab.component';
import { SettingsService } from '../settings/settings.service';

declare const android: any;
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
    Application.android?.on(Application.android.activityBackPressedEvent, (args: any) => {
      if (this.state.goBack()) {
        args.cancel = true;
      }
    });
    effect(() => {
      const light = this.settings.isLight();
      setTimeout(() => this.applySystemBars(light), 0);
    });
  }

  private applySystemBars(light: boolean): void {
    const activity = Application.android?.foregroundActivity ?? Application.android?.startActivity;
    if (!activity) {
      setTimeout(() => this.applySystemBars(light), 300);
      return;
    }
    const window = activity.getWindow();
    const top = new Color(light ? '#f6f2fb' : '#100a1d').android;
    const bottom = new Color(light ? '#ffffff' : '#170f25').android;
    window.setStatusBarColor(top);
    window.setNavigationBarColor(bottom);
    const decor = window.getDecorView();
    decor.setBackgroundColor(top);
    const flags = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    const current = decor.getSystemUiVisibility();
    decor.setSystemUiVisibility(light ? current | flags : current & ~flags);
    if (android.os.Build.VERSION.SDK_INT >= 30) {
      const controller = window.getInsetsController();
      const mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
      controller?.setSystemBarsAppearance(light ? mask : 0, mask);
    }
  }
}
