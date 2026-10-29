import { Component, inject, NO_ERRORS_SCHEMA } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { AppStateService } from '../core/app-state.service';
import { PlayerService } from './player.service';

@Component({
  selector: 'app-player-tab',
  imports: [NativeScriptCommonModule],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './player-tab.component.html',
})
export class PlayerTabComponent {
  readonly player = inject(PlayerService);
  readonly state = inject(AppStateService);

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
