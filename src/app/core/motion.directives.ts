import { Directive, ElementRef, inject, Input, OnChanges, OnDestroy, OnInit } from '@angular/core';
import { CoreTypes, TouchGestureEventData, View } from '@nativescript/core';

// Fade + slide-up when the view is created.
@Directive({ selector: '[appEnter]' })
export class EnterDirective implements OnInit {
  private readonly view = inject(ElementRef).nativeElement as View;

  ngOnInit(): void {
    this.view.opacity = 0;
    this.view.translateY = 14;
    const run = () =>
      this.view
        .animate({
          opacity: 1,
          translate: { x: 0, y: 0 },
          duration: 240,
          curve: CoreTypes.AnimationCurve.easeOut,
        })
        .catch(() => {
          this.view.opacity = 1;
          this.view.translateY = 0;
        });
    if (this.view.isLoaded) {
      run();
    } else {
      this.view.once('loaded', run);
    }
  }
}

const SPIN_TICK_MS = 40;

// Rotates the view continuously while the bound value is true; keeps its angle when paused.
@Directive({ selector: '[appSpin]' })
export class SpinDirective implements OnChanges, OnDestroy {
  @Input('appSpin') spinning = false;
  @Input() spinPeriod = 4;
  private readonly view = inject(ElementRef).nativeElement as View;
  private timer: ReturnType<typeof setInterval> | null = null;

  ngOnChanges(): void {
    this.stop();
    if (this.spinning) {
      const step = (360 * SPIN_TICK_MS) / (Math.max(0.5, this.spinPeriod) * 1000);
      this.timer = setInterval(() => {
        this.view.rotate = ((this.view.rotate || 0) + step) % 360;
      }, SPIN_TICK_MS);
    }
  }

  ngOnDestroy(): void {
    this.stop();
  }

  private stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}

// Animates the view rotation to the bound angle.
@Directive({ selector: '[appRotateTo]' })
export class RotateToDirective implements OnChanges {
  @Input('appRotateTo') angle = 0;
  private readonly view = inject(ElementRef).nativeElement as View;

  ngOnChanges(): void {
    this.view
      .animate({ rotate: this.angle, duration: 600, curve: CoreTypes.AnimationCurve.easeInOut })
      .catch(() => (this.view.rotate = this.angle));
  }
}

// Slight scale-down while pressed.
@Directive({ selector: '[appPress]' })
export class PressDirective implements OnInit, OnDestroy {
  private readonly view = inject(ElementRef).nativeElement as View;
  private readonly onTouch = (args: TouchGestureEventData) => {
    if (args.action === 'down') {
      this.scale(0.93, 90);
    } else if (args.action === 'up' || args.action === 'cancel') {
      this.scale(1, 140);
    }
  };

  ngOnInit(): void {
    this.view.on('touch', this.onTouch);
  }

  ngOnDestroy(): void {
    this.view.off('touch', this.onTouch);
  }

  private scale(value: number, duration: number): void {
    this.view
      .animate({ scale: { x: value, y: value }, duration, curve: CoreTypes.AnimationCurve.easeOut })
      .catch(() => undefined);
  }
}
