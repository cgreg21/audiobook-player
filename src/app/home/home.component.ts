import { Component, computed, effect, inject, NO_ERRORS_SCHEMA, signal } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { Application, Color, Page, ScrollView } from '@nativescript/core';
import { CatalogService } from '../library/catalog.service';
import { AppStateService } from '../core/app-state.service';
import { EnterDirective } from '../core/motion.directives';
import { BookDetailComponent } from '../library/book-detail.component';
import { LibraryTabComponent } from '../library/library-tab.component';
import { OptionsTabComponent } from '../options/options-tab.component';
import { PlayerTabComponent } from '../player/player-tab.component';
import { PodcastsTabComponent } from '../podcasts/podcasts-tab.component';
import { PALETTES, SettingsService } from '../settings/settings.service';

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
  private readonly catalog = inject(CatalogService);
  readonly pull = signal(0);
  private startY = -1;
  private pullActive = false;
  readonly showLoader = computed(
    () =>
      this.state.activeTab() === 'library' &&
      !this.state.selectedBook() &&
      (this.pull() > 20 || this.catalog.refreshing() || !!this.catalog.libraryProgress()),
  );

  onTouch(args: any): void {
    if (this.state.activeTab() !== 'library' || this.state.selectedBook()) {
      return;
    }
    const y = args.getY();
    if (args.action === 'down') {
      this.startY = (args.object as ScrollView).verticalOffset <= 0 ? y : -1;
      this.pullActive = false;
    } else if (args.action === 'move' && this.startY >= 0) {
      const delta = y - this.startY;
      if (delta > 0 && (args.object as ScrollView).verticalOffset <= 0) {
        this.pullActive = true;
        this.pull.set(Math.min(60, delta / 3));
      } else if (delta <= 0) {
        this.pull.set(0);
      }
    } else if (args.action === 'up' || args.action === 'cancel') {
      const trigger = this.pullActive && this.pull() >= 40;
      this.pull.set(0);
      this.startY = -1;
      this.pullActive = false;
      if (trigger) {
        this.refreshAll();
      }
    }
  }

  private refreshAll(): void {
    try {
      this.catalog.reloadFolders();
    } catch {
      this.state.showError('Actualisation impossible', 'Le dossier n’a pas pu être lu.');
    }
    void this.catalog.refreshFeeds();
  }

  constructor() {
    inject(Page).actionBarHidden = true;
    Application.android?.on(Application.android.activityBackPressedEvent, (args: any) => {
      if (this.state.goBack()) {
        args.cancel = true;
      }
    });
    effect(() => {
      const light = this.settings.isLight();
      const palette = PALETTES.find((p) => p.value === this.settings.palette()) ?? PALETTES[0];
      const colors = light ? palette.light : palette.dark;
      setTimeout(() => this.applySystemBars(light, colors.bg, colors.nav), 0);
    });
  }

  private applySystemBars(light: boolean, bg: string, nav: string): void {
    const activity = Application.android?.foregroundActivity ?? Application.android?.startActivity;
    if (!activity) {
      setTimeout(() => this.applySystemBars(light, bg, nav), 300);
      return;
    }
    const window = activity.getWindow();
    const top = new Color(bg).android;
    const bottom = new Color(nav).android;
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
