import { Component, computed, DestroyRef, ElementRef, inject, NO_ERRORS_SCHEMA, signal, viewChild } from '@angular/core';
import { NativeScriptCommonModule } from '@nativescript/angular';
import { CoreTypes, GestureStateTypes, PanGestureEventData, View } from '@nativescript/core';
import { AppStateService } from '../core/app-state.service';
import { SpinDirective, RotateToDirective } from '../core/motion.directives';
import { PlayerService } from './player.service';

const DRAWER_PEEK = 24;
const DRAWER_RUBBER = 0.1;

@Component({
  selector: 'app-player-tab',
  imports: [NativeScriptCommonModule, SpinDirective, RotateToDirective],
  schemas: [NO_ERRORS_SCHEMA],
  templateUrl: './player-tab.component.html',
})
export class PlayerTabComponent {
  readonly player = inject(PlayerService);
  readonly state = inject(AppStateService);

  readonly drawerRef = viewChild<ElementRef>('drawer');
  readonly drawerHeight = computed(() => (this.state.landscape() ? 300 : 440));
  readonly closedY = computed(() => this.drawerHeight() - DRAWER_PEEK);
  readonly drawerProgress = signal(0);
  readonly scrimOpacity = computed(() => Math.min(1, Math.max(0, this.drawerProgress())));
  private drawerY = -1;
  private dragStartY = 0;
  private lastDelta = 0;
  private dragDirection = 0;

  constructor() {
    const handler = () => {
      if (this.state.activeTab() === 'player' && this.drawerProgress() >= 0.5) {
        this.closeDrawer();
        return true;
      }
      return false;
    };
    this.state.backHandlers.push(handler);
    inject(DestroyRef).onDestroy(() => {
      const i = this.state.backHandlers.indexOf(handler);
      if (i >= 0) {
        this.state.backHandlers.splice(i, 1);
      }
    });
  }

  toggleDrawer(): void {
    this.settleDrawer(this.drawerProgress() < 0.5);
  }

  closeDrawer(): void {
    this.settleDrawer(false);
  }

  onPan(args: PanGestureEventData): void {
    const drawer = this.drawerRef()?.nativeElement as View | undefined;
    if (!drawer) {
      return;
    }
    const closed = this.closedY();
    if (args.state === GestureStateTypes.began) {
      this.dragStartY = this.drawerProgress() >= 0.5 ? 0 : closed;
      this.lastDelta = 0;
      this.dragDirection = 0;
      return;
    }
    if (args.state === GestureStateTypes.ended || args.state === GestureStateTypes.cancelled) {
      const y = this.drawerY < 0 ? this.dragStartY : this.drawerY;
      const open = Math.abs(this.dragDirection) > 4 ? this.dragDirection < 0 : y < closed / 2;
      this.settleDrawer(open);
      return;
    }
    const delta = args.deltaY;
    this.dragDirection = delta - this.lastDelta;
    this.lastDelta = delta;
    let y = this.dragStartY + delta;
    // Rubber band: past either end, only a fraction of the finger travel is applied.
    if (y < 0) {
      y = y * DRAWER_RUBBER;
    } else if (y > closed) {
      y = closed + (y - closed) * DRAWER_RUBBER;
    }
    this.drawerY = y;
    drawer.translateY = y;
    this.drawerProgress.set(Math.min(1, Math.max(0, 1 - y / closed)));
  }

  private settleDrawer(open: boolean): void {
    const drawer = this.drawerRef()?.nativeElement as View | undefined;
    if (!drawer) {
      return;
    }
    const target = open ? 0 : this.closedY();
    if (open) {
      this.drawerProgress.set(1);
    }
    drawer
      .animate({ translate: { x: 0, y: target }, duration: 300, curve: CoreTypes.AnimationCurve.easeOut })
      .then(() => {
        this.drawerY = target;
        this.drawerProgress.set(open ? 1 : 0);
      })
      .catch(() => undefined);
    if (!open) {
      this.drawerProgress.set(Math.min(this.drawerProgress(), 0.5));
    }
  }

  playChapter(index: number): void {
    const book = this.player.book();
    if (!book) {
      return;
    }
    if (index !== this.player.trackIndex()) {
      this.player.skipTo(index);
    }
    if (!this.player.playing()) {
      this.player.toggle();
    }
    this.settleDrawer(false);
  }

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
