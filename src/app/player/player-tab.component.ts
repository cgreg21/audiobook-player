import { Component, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService } from '../core/app-state.service';
import { SpinDirective, RotateToDirective } from '../core/motion.directives';
import { PlayerService } from './player.service';

@Component({
  selector: 'app-player-tab',
  imports: [NativeScriptCommonModule, SpinDirective, RotateToDirective],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './player-tab.component.html',
})
export class PlayerTabComponent {
  readonly player = inject(PlayerService);
  readonly state = inject(AppStateService);

  // Longer chapters turn slower: 1 s of period per minute, between 2.5 s and 14 s per revolution.
  spinPeriod(): number {
    return Math.min(14, Math.max(2.5, this.player.durationMs() / 60000));
  }

  // Needle goes from the outer groove (19°) to the inner one (45°) as the chapter progresses.
  armAngle(): number {
    if (!this.player.book()) {
      return 0;
    }
    const duration = this.player.durationMs();
    const fraction = duration > 0 ? Math.min(1, this.player.positionMs() / duration) : 0;
    return 19 + 26 * fraction;
  }

  onSeek(valueMs: number): void {
    if (Math.abs(valueMs - this.player.positionMs()) > 2000) {
      this.player.seekTo(valueMs);
    }
  }

  formatTime(ms: number): string {
    const total = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = String(total % 60).padStart(2, '0');
    return h ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
  }
}
