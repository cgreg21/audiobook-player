import { Component, computed, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService, Tab } from '../core/app-state.service';
import { PressDirective } from '../core/motion.directives';

const TITLES: Record<Tab, string> = {
  library: 'Ma bibliothèque',
  podcasts: 'Podcasts',
  options: 'Options',
  player: 'À l’écoute',
};

@Component({
  selector: 'app-top-bar',
  imports: [NativeScriptCommonModule, PressDirective],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './top-bar.component.html',
})
export class TopBarComponent {
  readonly state = inject(AppStateService);
  readonly title = computed(() => TITLES[this.state.activeTab()]);
}
