import { Directive, ElementRef, inject, OnDestroy, OnInit } from '@angular/core';
import { CoreTypes, TouchGestureEventData, View } from '@nativescript/core';

// Fade + slide-up when the view is created.
@Directive({ selector: '[appEnter]' })
export class EnterDirective implements OnInit {
  private readonly view = inject(ElementRef).nativeElement as View;

  ngOnInit(): void {
    this.view.opacity = 0;
    this.view.translateY = 14;
    this.view
      .animate({
        opacity: 1,
        translate: { x: 0, y: 0 },
        duration: 240,
        curve: CoreTypes.AnimationCurve.easeOut,
      })
      .catch(() => undefined);
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
