import { Component, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService } from '../core/app-state.service';
import { EnterDirective, PressDirective } from '../core/motion.directives';
import { PlayerService } from '../player/player.service';

@Component({
  selector: 'app-mini-player',
  templateUrl: './mini-player.component.html',
  imports: [NativeScriptCommonModule, EnterDirective, PressDirective],
  schemas: [NO_ERRORS_SCHEMA],
})
export class MiniPlayerComponent {
  readonly player = inject(PlayerService);
  readonly state = inject(AppStateService);

  fraction(): number {
    const duration = this.player.durationMs();
    return duration > 0 ? Math.min(1, Math.max(0, this.player.positionMs() / duration)) : 0;
  }
}
