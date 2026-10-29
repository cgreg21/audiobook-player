import { Component, computed, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService, Tab } from '../core/app-state.service';
import { PressDirective } from '../core/motion.directives';
import { CatalogService } from '../library/catalog.service';

@Component({
  selector: 'app-bottom-nav',
  imports: [NativeScriptCommonModule, PressDirective],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './bottom-nav.component.html',
})
export class BottomNavComponent {
  readonly state = inject(AppStateService);
  private readonly catalog = inject(CatalogService);
  readonly hasNew = computed(() => this.catalog.books().some((b) => this.catalog.isNew(b)));
  readonly items: { tab: Tab; icon: string }[] = [
    { tab: 'library', icon: '\ue758' },
    { tab: 'podcasts', icon: '\ue326' },
    { tab: 'player', icon: '\ue33c' },
  ];
}
