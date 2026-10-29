import { Injectable, signal } from '@angular/core';

export interface PickCandidate {
  asin: string;
  title: string;
  author?: string;
}

export interface PickRequest {
  title: string;
  author?: string;
  candidates: PickCandidate[];
}

@Injectable({ providedIn: 'root' })
export class BookPickerService {
  readonly request = signal<PickRequest | null>(null);
  private resolver: ((asin: string | null) => void) | null = null;

  // Attend le choix de l'utilisateur : ASIN retenu, ou null si annulé.
  choose(title: string, author: string | undefined, candidates: PickCandidate[]): Promise<string | null> {
    return new Promise((resolve) => {
      this.resolver = resolve;
      this.request.set({ title, author, candidates });
    });
  }

  close(asin: string | null): void {
    const resolve = this.resolver;
    this.resolver = null;
    this.request.set(null);
    resolve?.(asin);
  }
}
