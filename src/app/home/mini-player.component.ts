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

  progressColumns(): string {
    const duration = this.player.durationMs();
    const fraction = duration > 0 ? Math.min(1, this.player.positionMs() / duration) : 0;
    return `${Math.max(fraction, 0.001)}*, ${Math.max(1 - fraction, 0.001)}*`;
  }
}
