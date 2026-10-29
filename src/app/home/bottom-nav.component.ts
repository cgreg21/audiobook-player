import { Component, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService, Tab } from '../core/app-state.service';
import { PressDirective } from '../core/motion.directives';

@Component({
  selector: 'app-bottom-nav',
  imports: [NativeScriptCommonModule, PressDirective],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './bottom-nav.component.html',
})
export class BottomNavComponent {
  readonly state = inject(AppStateService);
  readonly items: { tab: Tab; icon: string }[] = [
    { tab: 'library', icon: '▤' },
    { tab: 'podcasts', icon: '◎' },
    { tab: 'player', icon: '♫' },
  ];
}
