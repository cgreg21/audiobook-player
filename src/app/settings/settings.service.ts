import { inject, Injectable, signal } from '@angular/core';
import { Application, Utils } from '@nativescript/core';
import { DatabaseService } from '../data/database.service';

declare const android: any;
declare const java: any;

export type ThemeMode = 'system' | 'dark' | 'light';
export type Accent = 'violet' | 'coral' | 'emerald' | 'blue';
export type StorageLocation = 'internal' | 'external';

@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly db = inject(DatabaseService);

  readonly themeMode = signal<ThemeMode>(this.db.getSetting('theme.mode', 'dark') as ThemeMode);
  readonly accent = signal<Accent>(this.db.getSetting('theme.accent', 'violet') as Accent);
  readonly storage = signal<StorageLocation>(this.db.getSetting('storage.location', 'internal') as StorageLocation);

  isLight(): boolean {
    const mode = this.themeMode();
    return mode === 'light' || (mode === 'system' && Application.systemAppearance() === 'light');
  }

  setThemeMode(mode: ThemeMode): void {
    this.themeMode.set(mode);
    this.db.setSetting('theme.mode', mode);
  }

  setAccent(accent: Accent): void {
    this.accent.set(accent);
    this.db.setSetting('theme.accent', accent);
  }

  setStorage(location: StorageLocation): void {
    this.storage.set(location);
    this.db.setSetting('storage.location', location);
  }

  externalAvailable(): boolean {
    return this.externalRoot() !== null;
  }

  // Root folder for new downloads; falls back to internal storage if the external one is unavailable.
  downloadsRoot(): string {
    const root = this.storage() === 'external' ? this.externalRoot() : null;
    const base = root ?? this.internalRoot();
    const dir = new java.io.File(base, 'podcasts');
    dir.mkdirs();
    return String(dir.getAbsolutePath());
  }

  freeSpaceBytes(): number {
    const stat = new android.os.StatFs(this.internalRoot());
    return Number(stat.getAvailableBytes());
  }

  private internalRoot(): string {
    return String(Utils.android.getApplicationContext().getFilesDir().getAbsolutePath());
  }

  private externalRoot(): string | null {
    const dir = Utils.android.getApplicationContext().getExternalFilesDir(null);
    return dir ? String(dir.getAbsolutePath()) : null;
  }
}
